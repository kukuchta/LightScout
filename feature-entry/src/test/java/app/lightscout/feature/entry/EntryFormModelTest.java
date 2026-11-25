package app.lightscout.feature.entry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.entry.SourceKind;
import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.feature.entry.EntryFormModel.Event;
import app.lightscout.feature.entry.EntryFormModel.Message;
import app.lightscout.feature.entry.EntryFormUiState.Direction;
import app.lightscout.feature.entry.Fakes.FakeWriter;
import app.lightscout.feature.entry.Fakes.FixedClock;
import app.lightscout.feature.entry.Fakes.MapStore;
import app.lightscout.feature.entry.Fakes.Recorder;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.WriteKind;
import app.lightscout.usecases.WriteResult;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** CP-13 §9 JVM rows: validation copy, Save enabled, identifier, outcome mapping, similar entry; plus back/discard. */
public class EntryFormModelTest {

    private static final Instant NOW = Instant.parse("2024-05-10T12:00:00Z");
    private static final String EDIT_ID = "e-1";

    private FixedClock clock;
    private MapStore store;
    private FakeWriter writer;
    private Recorder recorder;
    private EntryFormModel model;

    @Before
    public void setUp() {
        clock = new FixedClock(NOW);
        store = new MapStore();
        writer = new FakeWriter();
    }

    private EntryFormModel open(String entryId, boolean online) {
        return open(entryId, entryId == null ? EntryType.CARBS : null, online);
    }

    private EntryFormModel open(String entryId, EntryType initialType, boolean online) {
        recorder = new Recorder();
        model = new EntryFormModel(entryId, initialType, writer, new EntryRules(), clock, store, Fakes.DIRECT, online);
        model.setListener(recorder);
        model.start();
        return model;
    }

    private EntryFormUiState s() {
        return model.state();
    }

    private void openEditLoaded(boolean online, EntryType type, Integer value, Instant time) {
        open(EDIT_ID, online);
        assertEquals(1, writer.loads.size());
        assertEquals(EDIT_ID, writer.loads.get(0).arg);
        writer.loads.get(0).complete(Result.ok(new EntryDraft(EDIT_ID, EntryMode.EDIT, type, value, time, 1234L)));
    }

    private static long utcDay(LocalDate d) {
        return d.toEpochDay() * Duration.ofDays(1).toMillis();
    }

    private void pickTime(LocalDate day, int hour, int minute) {
        model.onDatePicked(utcDay(day));
        model.onTimePicked(hour, minute);
    }

    // ------------------------------------------------------------------ validation copy

    @Test
    public void bgRange() {
        open(null, true);
        model.onTypeSelected(EntryType.BG_CHECK);
        model.onValueChanged("19");
        assertNull(s().valueError());   // below the minimum is incomplete while typing (D-147)
        assertFalse(s().saveEnabled());
        model.onSave();
        assertEquals(ValidationCode.BG_RANGE, s().valueError());
        model.onValueChanged("601");
        assertEquals(ValidationCode.BG_RANGE, s().valueError());
        model.onValueChanged("20");
        assertNull(s().valueError());
        assertTrue(s().saveEnabled());
        model.onValueChanged("600");
        assertTrue(s().saveEnabled());
        assertEquals(R.string.s02_error_bg_range, EntryCopy.validation(ValidationCode.BG_RANGE));
    }

    @Test
    public void carbsRange() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        assertNull(s().valueError());   // the untouched empty field shows no error (BUG-37), Save stays disabled
        assertFalse(s().saveEnabled());
        model.onValueChanged("0");
        assertNull(s().valueError());   // D-147: still incomplete while typing, Save stays disabled
        assertFalse(s().saveEnabled());
        model.onSave();   // Done / Save: the error is no longer premature
        assertEquals(ValidationCode.CARBS_RANGE, s().valueError());
        model.onValueChanged("00");
        assertNull(s().valueError());   // typing again withdraws the forced error
        model.onValueChanged("1");
        model.onValueChanged("251");
        assertEquals(ValidationCode.CARBS_RANGE, s().valueError());
        assertFalse(s().saveEnabled());
        model.onValueChanged("15");
        assertNull(s().valueError());
        assertEquals(R.string.s02_error_carbs_range, EntryCopy.validation(ValidationCode.CARBS_RANGE));
    }

    @Test
    public void bolusRangeAndCommaDecimal() {
        open(null, true);
        model.onTypeSelected(EntryType.BOLUS);
        for (String bad : new String[] {"25.001", "1.2340"}) {   // can no longer become valid: error at once
            model.onValueChanged(bad);
            assertEquals(bad, ValidationCode.BOLUS_RANGE, s().valueError());
            assertFalse(bad, s().saveEnabled());
        }
        for (String incomplete : new String[] {"1.", "0.001", "0.024"}) {   // D-147: no error while typing
            model.onValueChanged(incomplete);
            assertNull(incomplete, s().valueError());
            assertFalse(incomplete, s().saveEnabled());
        }
        model.onSave();
        assertEquals(ValidationCode.BOLUS_RANGE, s().valueError());
        model.onValueChanged("1,5");
        assertNull(s().valueError());
        assertTrue(s().saveEnabled());
        model.onSave();
        assertEquals(1, writer.saves.size());
        assertEquals(Integer.valueOf(1500), writer.saves.get(0).arg.value());
        assertEquals(EntryType.BOLUS, writer.saves.get(0).arg.type());
        assertEquals(R.string.s02_error_bolus_range, EntryCopy.validation(ValidationCode.BOLUS_RANGE));
    }

    @Test
    public void emptyValueShowsNoErrorButDisablesSave() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        model.onValueChanged("");
        assertNull(s().valueError());
        assertFalse(s().saveEnabled());
        assertTrue(s().valueVisible());
    }

    @Test
    public void timeInTheFuture() {
        open(null, EntryType.SITE_CHANGE, true);
        pickTime(LocalDate.of(2024, 5, 10), 12, 2);   // now + 2 min
        assertEquals(ValidationCode.TIME_FUTURE, s().timeError());
        assertFalse(s().saveEnabled());
        assertEquals(R.string.s02_error_time_future, EntryCopy.validation(ValidationCode.TIME_FUTURE));
        model.onTimeNow();
        assertNull(s().timeError());
        assertNull(s().time());
        assertTrue(s().saveEnabled());
    }

    @Test
    public void timeTooOld() {
        open(null, EntryType.SITE_CHANGE, true);
        pickTime(LocalDate.of(2024, 5, 3), 11, 59);   // now − 7 d − 1 min
        assertEquals(ValidationCode.TIME_TOO_OLD, s().timeError());
        assertFalse(s().saveEnabled());
        assertEquals(R.string.s02_error_time_too_old, EntryCopy.validation(ValidationCode.TIME_TOO_OLD));
        pickTime(LocalDate.of(2024, 5, 3), 12, 0);
        assertNull(s().timeError());
        assertEquals(Instant.parse("2024-05-03T12:00:00Z"), s().time());
    }

    @Test
    public void timeIsValidatedAgainAtSave() {
        open(null, EntryType.SITE_CHANGE, true);
        pickTime(LocalDate.of(2024, 5, 3), 12, 0);
        assertTrue(s().saveEnabled());
        clock.now = NOW.plus(Duration.ofMinutes(1));
        model.onSave();
        assertTrue(writer.saves.isEmpty());
        assertEquals(ValidationCode.TIME_TOO_OLD, s().timeError());
    }

    @Test
    public void pickerCancelKeepsTheValue() {
        open(null, EntryType.SITE_CHANGE, true);
        pickTime(LocalDate.of(2024, 5, 10), 9, 30);
        model.onDatePicked(utcDay(LocalDate.of(2024, 5, 9)));
        model.onPickerCancelled();
        assertEquals(Instant.parse("2024-05-10T09:30:00Z"), s().time());
        assertEquals(9, model.pickerClock()[0]);
        assertEquals(30, model.pickerClock()[1]);
    }

    // ------------------------------------------------------------------ Save enabled

    @Test
    public void routeDefaultsAreNotSaveValidForCarbs() {
        open(null, true);
        assertEquals(EntryType.CARBS, s().type());
        assertFalse(s().saveEnabled());
        assertFalse(s().helperChooseType());
        assertEquals(R.string.s02_helper_choose_type, EntryCopy.validation(ValidationCode.TYPE_REQUIRED));
    }

    @Test
    public void siteChangeEnablesSaveWithoutValue() {
        open(null, EntryType.SITE_CHANGE, true);
        assertFalse(s().valueVisible());
        assertFalse(s().helperChooseType());
        assertTrue(s().saveEnabled());
        model.onSave();
        EntryDraft d = writer.saves.get(0).arg;
        assertEquals(EntryType.SITE_CHANGE, d.type());
        assertNull(d.value());
        assertEquals("Now uses the Save validation instant", NOW, d.time());
        assertEquals(EntryMode.CREATE, d.mode());
    }

    @Test
    public void offlineCreateDisablesSaveWithOfflineText() {
        open(null, EntryType.SITE_CHANGE, false);
        assertFalse(s().saveEnabled());
        assertEquals(Message.OFFLINE_CREATE, s().message());
        assertEquals(R.string.s02_offline_create, EntryCopy.message(Message.OFFLINE_CREATE));
        model.onSave();
        assertTrue(writer.saves.isEmpty());
        model.setOnline(true);
        assertEquals(Message.NONE, s().message());
        assertTrue(s().saveEnabled());
    }

    @Test
    public void offlineEditDisablesSaveAndDelete() {
        openEditLoaded(false, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        assertEquals(Message.OFFLINE_EDIT, s().message());
        assertFalse(s().saveEnabled());
        assertTrue(s().deleteVisible());
        assertFalse(s().deleteEnabled());
        assertEquals(R.string.s02_offline_edit, EntryCopy.message(Message.OFFLINE_EDIT));
        model.onDeleteRequested();
        assertFalse(s().deleteDialog());
    }

    // ------------------------------------------------------------------ identifier (GIR-3)

    @Test
    public void newDraftOncePerSessionAndRetryReusesIdentifier() {
        open(null, true);
        model.start();
        assertEquals(1, writer.newDrafts);
        model.onTypeSelected(EntryType.CARBS);
        model.onValueChanged("15");
        model.onSave();
        writer.saves.get(0).complete(Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT));
        assertEquals(Message.NS_NOT_RESPONDING, s().message());
        assertTrue(s().saveEnabled());
        model.onSave();
        assertEquals(2, writer.saves.size());
        assertEquals("draft-1", writer.saves.get(0).arg.identifier());
        assertEquals("draft-1", writer.saves.get(1).arg.identifier());
        assertEquals(1, writer.newDrafts);
    }

    @Test
    public void processDeathRestoresFormAndIdentifier() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        model.onValueChanged("15");
        pickTime(LocalDate.of(2024, 5, 10), 9, 5);
        model.onSave();   // in flight when the process dies

        FakeWriter after = new FakeWriter();
        writer = after;
        open(null, true);
        assertEquals(0, after.newDrafts);
        assertEquals("draft-1", model.identifier());
        assertEquals(EntryType.CARBS, s().type());
        assertEquals("15", s().valueText());
        assertEquals(Instant.parse("2024-05-10T09:05:00Z"), s().time());
        assertEquals(Message.UNSURE_SAVED, s().message());
        assertEquals(R.string.s02_error_unsure_saved, EntryCopy.message(Message.UNSURE_SAVED));
        assertFalse(s().saveEnabled());
        model.onSave();
        assertTrue(after.saves.isEmpty());
    }

    @Test
    public void processDeathWithoutSaveRestoresWithoutWarning() {
        open(null, true);
        model.onTypeSelected(EntryType.BG_CHECK);
        model.onValueChanged("123");
        writer = new FakeWriter();
        open(null, true);
        assertEquals(Message.NONE, s().message());
        assertEquals("123", s().valueText());
        assertEquals("draft-1", model.identifier());
    }

    @Test
    public void editUsesTheEntryIdAndExpectedSrvModified() {
        openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        assertEquals(0, writer.newDrafts);
        assertEquals(EntryMode.EDIT, s().mode());
        assertTrue(s().typeLocked());
        assertEquals("15", s().valueText());
        assertEquals(1, s().valueFieldVersion());
        model.onTypeSelected(EntryType.BOLUS);
        assertEquals("type is locked in edit (R-75)", EntryType.CARBS, s().type());
        model.onValueChanged("20");
        model.onSave();
        EntryDraft d = writer.saves.get(0).arg;
        assertEquals(EDIT_ID, d.identifier());
        assertEquals(EntryMode.EDIT, d.mode());
        assertEquals(Integer.valueOf(20), d.value());
        assertEquals(Long.valueOf(1234L), d.expectedSrvModifiedMs());
    }

    @Test
    public void editRestoredAfterProcessDeathKeepsEditsWithoutReload() {
        openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        model.onValueChanged("30");
        writer = new FakeWriter();
        open(EDIT_ID, true);
        assertTrue(writer.loads.isEmpty());
        assertEquals("30", s().valueText());
        assertTrue(s().backGuard());
        model.onSave();
        assertEquals(Long.valueOf(1234L), writer.saves.get(0).arg.expectedSrvModifiedMs());
    }

    // ------------------------------------------------------------------ outcome mapping (R-51)

    @Test
    public void failureOutcomesMapToCopy() {
        Object[][] table = {
            {FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK, Message.OFFLINE_CREATE},
            {FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX, Message.NS_NOT_RESPONDING},
            {FetchOutcome.NS_NOT_RESPONDING, null, Message.NS_NOT_RESPONDING},
            {FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE, Message.NS_NOT_RESPONDING},
            {FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401, Message.TOKEN_REJECTED},
            {FetchOutcome.PERMISSION_MISSING, DetailCode.MISSING_WRITE, Message.TOKEN_REJECTED},
            {FetchOutcome.OK, DetailCode.REJECTED_400_422, Message.REJECTED},
        };
        for (Object[] row : table) {
            setUp();
            open(null, true);
            model.onTypeSelected(EntryType.CARBS);
            model.onValueChanged("15");
            model.onSave();
            assertTrue(s().progress());
            assertFalse(s().saveEnabled());
            writer.saves.get(0).complete(Result.fail((FetchOutcome) row[0], (String) row[1]));
            assertEquals(row[0] + "/" + row[1], row[2], s().message());
            assertFalse(s().progress());
            assertEquals("form intact", "15", s().valueText());
            assertTrue(recorder.events.stream().noneMatch(e -> e.kind() == Event.Kind.FINISH));
        }
        assertEquals(R.string.s02_error_ns_not_responding, EntryCopy.message(Message.NS_NOT_RESPONDING));
        assertEquals(R.string.s02_error_token_rejected, EntryCopy.message(Message.TOKEN_REJECTED));
        assertEquals(R.string.s02_error_rejected, EntryCopy.message(Message.REJECTED));
        assertEquals(0, EntryCopy.message(Message.NONE));
    }

    @Test
    public void editConflictKeepsFormAndDisablesSave() {
        for (String detail : new String[] {DetailCode.PRECONDITION_412, DetailCode.GONE_404_410,
            DetailCode.NOT_EDITABLE}) {
            setUp();
            openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
            model.onValueChanged("20");
            model.onSave();
            writer.saves.get(0).complete(Result.fail(FetchOutcome.OK, detail));
            assertEquals(detail, Message.CONFLICT, s().message());
            assertEquals("20", s().valueText());
            assertFalse(s().saveEnabled());
            assertFalse(s().deleteEnabled());
            assertFalse(s().fieldsEnabled());
        }
        assertEquals(R.string.s02_error_conflict, EntryCopy.message(Message.CONFLICT));
    }

    @Test
    public void successFinishesWithTheS01Message() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        model.onValueChanged("15");
        model.onSave();
        // dedup success (isDeduplication) is a normal CREATED result of IF-23 → success path
        writer.saves.get(0).complete(Result.ok(new WriteResult(WriteKind.CREATED, "draft-1")));
        Event last = recorder.events.get(recorder.events.size() - 1);
        assertEquals(Event.Kind.FINISH, last.kind());
        assertEquals(UiMessage.ENTRY_SAVED, last.result());
        assertFalse(s().backGuard());
    }

    @Test
    public void editSuccessIsUpdated() {
        openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        model.onValueChanged("20");
        model.onSave();
        writer.saves.get(0).complete(Result.ok(new WriteResult(WriteKind.UPDATED, EDIT_ID)));
        assertEquals(UiMessage.ENTRY_UPDATED, recorder.events.get(recorder.events.size() - 1).result());
    }

    @Test
    public void deleteConfirmedOnceAndFinishesDeleted() {
        openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        model.onDeleteRequested();
        assertTrue(s().deleteDialog());
        model.onDeleteConfirmed();
        model.onDeleteConfirmed();   // GIR-1 double tap
        assertEquals(1, writer.deletes.size());
        assertEquals(EDIT_ID, writer.deletes.get(0).arg);
        assertTrue(s().progress());
        assertFalse(s().deleteEnabled());
        writer.deletes.get(0).complete(Result.ok(new WriteResult(WriteKind.DELETED, EDIT_ID)));
        Event last = recorder.events.get(recorder.events.size() - 1);
        assertEquals(Event.Kind.FINISH, last.kind());
        assertEquals(UiMessage.ENTRY_DELETED, last.result());
    }

    @Test
    public void delete404IsConflict() {
        openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        model.onDeleteRequested();
        model.onDeleteConfirmed();
        writer.deletes.get(0).complete(Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410));
        assertEquals(Message.CONFLICT, s().message());
        assertFalse(s().deleteEnabled());
    }

    @Test
    public void deleteCancelKeepsTheForm() {
        openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        model.onDeleteRequested();
        model.onDeleteCancelled();
        assertFalse(s().deleteDialog());
        assertTrue(writer.deletes.isEmpty());
    }

    @Test
    public void doubleSaveCallsOnce() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        model.onValueChanged("15");
        model.onSave();
        model.onSave();
        assertEquals(1, writer.saves.size());
        assertFalse(s().fieldsEnabled());
    }

    @Test
    public void failedLoadForEditIsConflict() {
        open(EDIT_ID, true);
        assertTrue(s().progress());
        assertFalse(s().fieldsEnabled());
        writer.loads.get(0).complete(Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE));
        assertEquals(Message.CONFLICT, s().message());
        assertFalse(s().saveEnabled());
        assertFalse(s().deleteEnabled());
        assertFalse(s().progress());
    }

    @Test
    public void callbacksAfterClearAreIgnored() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        model.onValueChanged("15");
        model.onSave();
        int events = recorder.events.size();
        model.clear();
        writer.saves.get(0).complete(Result.ok(new WriteResult(WriteKind.CREATED, "draft-1")));
        assertEquals(events, recorder.events.size());
    }

    // ------------------------------------------------------------------ similar entry (DS:D-027)

    @Test
    public void similarSameTypeEightMinutesEarlierIsShownWithSource() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        assertNull(s().similar());
        Entry e = Fakes.entry("x", EntryType.CARBS, NOW.minus(Duration.ofMinutes(8)), 20, "xDrip+", false);
        writer.lastSimilar().complete(Result.ok(List.of(e)));
        EntryFormUiState.Similar sim = s().similar();
        assertNotNull(sim);
        assertEquals(e, sim.entry());
        assertEquals(8, sim.minutes());
        assertEquals(Direction.EARLIER, sim.direction());
        assertEquals(SourceKind.UPLOADER, sim.source().kind());
        assertEquals("xDrip+", sim.source().uploaderName());
        assertEquals(R.string.s02_similar_entry_earlier, EntryCopy.similar(Direction.EARLIER));
    }

    @Test
    public void similarOtherTypeOrElevenMinutesIsHidden() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        writer.lastSimilar().complete(Result.ok(List.of(
                Fakes.entry("a", EntryType.BOLUS, NOW.minus(Duration.ofMinutes(2)), null, null, true),
                Fakes.entry("b", EntryType.CARBS, NOW.minus(Duration.ofMinutes(11)), 10, null, true))));
        assertNull(s().similar());
    }

    @Test
    public void similarLaterSameTimeAndSources() {
        open(null, EntryType.SITE_CHANGE, true);
        pickTime(LocalDate.of(2024, 5, 10), 11, 0);
        EntryDraft q = writer.lastSimilar().arg;
        assertEquals(Instant.parse("2024-05-10T11:00:00Z"), q.time());
        writer.lastSimilar().complete(Result.ok(List.of(
                Fakes.entry("a", EntryType.SITE_CHANGE, Instant.parse("2024-05-10T11:05:30Z"), null, null, true))));
        assertEquals(Direction.LATER, s().similar().direction());
        assertEquals(5, s().similar().minutes());
        assertEquals(SourceKind.LIGHTSCOUT, s().similar().source().kind());

        model.onTimeNow();
        writer.lastSimilar().complete(Result.ok(List.of(
                Fakes.entry("b", EntryType.SITE_CHANGE, NOW.minusSeconds(20), null, "undefined", false))));
        assertEquals(Direction.SAME_TIME, s().similar().direction());
        assertEquals(SourceKind.NIGHTSCOUT, s().similar().source().kind());
        assertEquals(R.string.s01_tooltip_source_nightscout, EntryCopy.source(SourceKind.NIGHTSCOUT));
        assertEquals(R.string.s01_tooltip_source_lightscout, EntryCopy.source(SourceKind.LIGHTSCOUT));
    }

    @Test
    public void staleSimilarResultIsIgnoredAndEditExcludesItself() {
        open(null, true);
        model.onTypeSelected(EntryType.CARBS);
        Fakes.Call<EntryDraft, List<Entry>> first = writer.lastSimilar();
        model.onTypeSelected(EntryType.BG_CHECK);
        first.complete(Result.ok(List.of(
                Fakes.entry("x", EntryType.CARBS, NOW.minus(Duration.ofMinutes(1)), 20, null, true))));
        assertNull(s().similar());

        setUp();
        openEditLoaded(true, EntryType.CARBS, 15, NOW);
        writer.lastSimilar().complete(Result.ok(List.of(
                Fakes.entry(EDIT_ID, EntryType.CARBS, NOW, 15, null, true))));
        assertNull(s().similar());
    }

    // ------------------------------------------------------------------ close / discard (C-S02-14)

    @Test
    public void closeWithoutChangesClosesWithoutDialog() {
        open(null, true);
        assertFalse(s().backGuard());
        model.onCloseRequested();
        assertFalse(s().discardDialog());
        assertEquals(Event.Kind.CLOSE, recorder.events.get(recorder.events.size() - 1).kind());
    }

    @Test
    public void typeChosenAsksBeforeDiscarding() {
        open(null, true);
        model.onTypeSelected(EntryType.BOLUS);
        assertTrue(s().backGuard());
        model.onCloseRequested();
        assertTrue(s().discardDialog());
        assertTrue(recorder.events.stream().noneMatch(e -> e.kind() == Event.Kind.CLOSE));
        model.onDiscardCancelled();
        assertFalse(s().discardDialog());
        assertEquals(EntryType.BOLUS, s().type());
        model.onCloseRequested();
        model.onDiscardConfirmed();
        assertEquals(Event.Kind.CLOSE, recorder.events.get(recorder.events.size() - 1).kind());
        assertFalse(s().backGuard());
    }

    @Test
    public void editAsksOnlyAfterAChange() {
        openEditLoaded(true, EntryType.CARBS, 15, NOW.minus(Duration.ofHours(1)));
        assertFalse(s().backGuard());
        model.onValueChanged("16");
        assertTrue(s().backGuard());
        model.onValueChanged("15");
        assertFalse(s().backGuard());
        model.onTimeNow();
        assertTrue(s().backGuard());
    }

    @Test
    public void openDialogSurvivesRestore() {
        open(null, true);
        model.onTypeSelected(EntryType.BOLUS);
        model.onCloseRequested();
        open(null, true);
        assertTrue(s().discardDialog());
    }

    @Test
    public void typeAndHelperActionsNeverRequestIme() {
        open(null, true);
        model.onTypeSelected(EntryType.BOLUS);
        assertTrue(recorder.events.isEmpty());
        int n = recorder.events.size();
        model.onTypeSelected(EntryType.SENSOR_CHANGE);
        assertEquals(n, recorder.events.size());
    }

    @Test
    public void loadedValueText() {
        assertEquals("1", EntryFormModel.valueTextOf(EntryType.BOLUS, 1000));
        assertEquals("1.5", EntryFormModel.valueTextOf(EntryType.BOLUS, 1500));
        assertEquals("1.25", EntryFormModel.valueTextOf(EntryType.BOLUS, 1250));
        assertEquals("0.025", EntryFormModel.valueTextOf(EntryType.BOLUS, 25));
        assertEquals("1.234", EntryFormModel.valueTextOf(EntryType.BOLUS, 1234));
        assertEquals("25", EntryFormModel.valueTextOf(EntryType.BOLUS, 25000));
        assertEquals("120", EntryFormModel.valueTextOf(EntryType.BG_CHECK, 120));
        assertEquals("", EntryFormModel.valueTextOf(EntryType.SITE_CHANGE, null));
    }

    @Test
    public void bolusEditRestoresRawPrecisionAndDirtyBaselineWithoutReload() {
        openEditLoaded(true, EntryType.BOLUS, 1234, NOW.minusSeconds(60));
        assertEquals("1.234", s().valueText());
        assertFalse(s().backGuard());
        model.onValueChanged(" 1,230 ");
        assertTrue(s().backGuard());
        writer = new FakeWriter();
        open(EDIT_ID, true);
        assertTrue(writer.loads.isEmpty());
        assertEquals(" 1,230 ", s().valueText());
        assertTrue(s().backGuard());
        model.onValueChanged("1.234");
        assertFalse(s().backGuard());
        model.onSave();
        assertEquals(Integer.valueOf(1234), writer.saves.get(0).arg.value());
    }
}
