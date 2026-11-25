package app.lightscout.feature.entry;

import static org.junit.Assert.*;

import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.feature.entry.EntryFormModel.ClosePath;
import app.lightscout.feature.entry.EntryFormModel.Event;
import app.lightscout.usecases.WriteKind;
import app.lightscout.usecases.WriteResult;
import java.time.Instant;
import java.time.Duration;
import org.junit.Test;

/** R-92 / R-95 contract tests; platform paths are wired to these requests, not simulated hardware. */
public class EntrySheetContractTest {
    private final Fakes.FixedClock clock = new Fakes.FixedClock(Instant.parse("2026-10-01T12:00:00Z"));
    private final Fakes.FakeWriter writer = new Fakes.FakeWriter();
    private final Fakes.MapStore store = new Fakes.MapStore();
    private Fakes.Recorder recorder;

    private EntryFormModel open(String id, EntryType type) {
        EntryFormModel model = new EntryFormModel(id, type, writer, new EntryRules(), clock, store,
                Fakes.DIRECT, true);
        recorder = new Fakes.Recorder();
        model.setListener(recorder);
        model.start();
        return model;
    }

    private EntryFormModel edit() {
        EntryFormModel model = open("own", null);
        writer.loads.get(writer.loads.size() - 1).complete(Result.ok(new EntryDraft("own", EntryMode.EDIT,
                EntryType.BOLUS, 1234, clock.now.minusSeconds(3600), 42L)));
        return model;
    }

    @Test
    public void fiveRoutesHaveCleanDefaultsAndOnlyNumericRoutesSwitchTabs() {
        for (EntryType t : new EntryType[] {EntryType.CARBS, EntryType.BOLUS, EntryType.BG_CHECK,
                EntryType.SITE_CHANGE, EntryType.SENSOR_CHANGE}) {
            Fakes.MapStore fresh = new Fakes.MapStore();
            EntryFormModel m = new EntryFormModel(null, t, writer, new EntryRules(), clock, fresh, Fakes.DIRECT, true);
            m.start();
            assertEquals(t, m.state().type());
            assertEquals("D-146: every route starts empty", "", m.state().valueText());
            assertFalse(m.state().backGuard());
            assertNull(m.state().time());
            assertEquals("a value-less route is Save-ready, a numeric route needs the typed value",
                    !EntryFormModel.needsValue(t), m.state().saveEnabled());
            assertNull("no error on the untouched empty field", m.state().valueError());
            m.onTypeSelected(EntryType.SITE_CHANGE);
            assertEquals(t, m.state().type());
            if (!EntryFormModel.needsValue(t)) {
                m.onTypeSelected(EntryType.CARBS);
                assertEquals(t, m.state().type());
            }
        }
    }

    @Test
    public void invalidRoutesAreProgrammingErrors() {
        for (EntryType t : new EntryType[] {null, EntryType.PUMP_ALERT, EntryType.TEMP_BASAL}) {
            assertThrows(IllegalArgumentException.class, () -> open(null, t));
        }
        assertThrows(IllegalArgumentException.class, () -> open("", null));
        assertThrows(IllegalArgumentException.class, () -> open("own", EntryType.CARBS));
    }

    @Test
    public void typedBolusIsExactAndNeedsNoDoseGrid() {
        EntryFormModel m = open(null, EntryType.BOLUS);
        m.onValueChanged("1,23");
        assertTrue(m.state().saveEnabled());
        m.onValueChanged("1.234");
        assertTrue(m.state().saveEnabled());
        m.onSave();
        assertEquals(Integer.valueOf(1234), writer.saves.get(0).arg.value());
        assertEquals(clock.now, writer.saves.get(0).arg.time());
    }

    @Test
    public void syntaxAndBoundsRejectRatherThanRound() {
        EntryFormModel m = open(null, EntryType.BOLUS);
        for (String raw : new String[] {"0", "0.024", "25.001", "1.2345", "1.", "1,", "-1", "+1", "1e0",
                "1 000", "", "NaN"}) {
            m.onValueChanged(raw);
            assertFalse(raw, m.state().saveEnabled());
        }
        for (String raw : new String[] {"0.025", "25", "1.23", "1.234", " 1,234 "}) {
            m.onValueChanged(raw);
            assertTrue(raw, m.state().saveEnabled());
        }
    }

    @Test
    public void carbsAndBgBoundsDecideSaveAndTheValueIsNeverCoerced() {
        EntryFormModel carbs = open(null, EntryType.CARBS);
        for (String raw : new String[] {"", "0", "251", "-1", "1.5", "abc"}) {
            carbs.onValueChanged(raw);
            assertFalse(raw, carbs.state().saveEnabled());
            assertEquals(raw, carbs.state().valueText());
        }
        for (String raw : new String[] {"1", "18", "250"}) {
            carbs.onValueChanged(raw);
            assertTrue(raw, carbs.state().saveEnabled());
        }
        EntryFormModel bg = new EntryFormModel(null, EntryType.BG_CHECK, writer, new EntryRules(), clock,
                new Fakes.MapStore(), Fakes.DIRECT, true);
        bg.start();
        assertFalse("empty BG is not Save-ready (no pre-filled 100)", bg.state().saveEnabled());
        for (String raw : new String[] {"19", "601"}) {
            bg.onValueChanged(raw);
            assertFalse(raw, bg.state().saveEnabled());
        }
        for (String raw : new String[] {"20", "123", "600"}) {
            bg.onValueChanged(raw);
            assertTrue(raw, bg.state().saveEnabled());
        }
    }

    @Test
    public void perTypeRawDraftsAndCommonTimeSurviveRestorationWithoutRouteReapplication() {
        EntryFormModel m = open(null, EntryType.CARBS);
        m.onValueChanged("015");
        m.onTypeSelected(EntryType.BOLUS);
        m.onValueChanged("1,23");
        m.onDatePicked(clock.now.minusSeconds(86400).toEpochMilli());
        m.onTimePicked(9, 30);
        Instant time = m.state().time();
        m.onTypeSelected(EntryType.BG_CHECK);
        m.onValueChanged("1.");
        m = open(null, EntryType.SENSOR_CHANGE);
        assertEquals(EntryType.BG_CHECK, m.state().type());
        assertEquals("1.", m.state().valueText());
        m.onTypeSelected(EntryType.CARBS);
        assertEquals("015", m.state().valueText());
        assertEquals(time, m.state().time());
        m.onTypeSelected(EntryType.BOLUS);
        assertEquals("1,23", m.state().valueText());
        assertEquals(time, m.state().time());
        assertEquals(1, writer.newDrafts);
        m.onSave();
        assertEquals(Integer.valueOf(1230), writer.saves.get(0).arg.value());
        assertEquals(EntryType.BOLUS, writer.saves.get(0).arg.type());
    }

    @Test
    public void inactiveChangesAreDirtyAndReturningAllDraftsToBaselineIsClean() {
        EntryFormModel m = open(null, EntryType.CARBS);
        m.onTypeSelected(EntryType.BOLUS);
        m.onValueChanged("1.");
        m.onTypeSelected(EntryType.CARBS);
        assertTrue(m.state().backGuard());
        m.onCloseRequested();
        assertTrue(m.state().discardDialog());
        m.onDiscardCancelled();
        m.onTypeSelected(EntryType.BOLUS);
        m.onValueChanged("");
        m.onTypeSelected(EntryType.CARBS);
        assertFalse(m.state().backGuard());
    }

    @Test
    public void everyClosePathUsesCleanDirtyAndWriteGuardAndBackHidesImeOnly() {
        for (ClosePath path : ClosePath.values()) {
            EntryFormModel clean = new EntryFormModel(null, EntryType.BG_CHECK, writer, new EntryRules(), clock,
                    new Fakes.MapStore(), Fakes.DIRECT, true);
            Fakes.Recorder events = new Fakes.Recorder();
            clean.setListener(events);
            clean.start();
            clean.onCloseRequested(path, false);
            assertEquals(path.name(), Event.Kind.CLOSE, events.events.get(0).kind());
            clean.onCloseRequested(path, false);
            assertEquals(1, events.events.size());

            EntryFormModel dirty = new EntryFormModel(null, EntryType.CARBS, writer, new EntryRules(), clock,
                    new Fakes.MapStore(), Fakes.DIRECT, true);
            dirty.setListener(events = new Fakes.Recorder());
            dirty.start();
            dirty.onValueChanged("15");
            dirty.onCloseRequested(path, false);
            assertTrue(path.name(), dirty.state().discardDialog());
            dirty.onDiscardCancelled();
            assertEquals("15", dirty.state().valueText());
            dirty.onSave();
            dirty.onCloseRequested(path, false);
            dirty.onDiscardConfirmed();
            assertTrue(path.name(), events.events.isEmpty());
            assertFalse(path.name(), dirty.state().discardDialog());
            assertFalse(dirty.dismissalEnabled());
        }
        EntryFormModel m = open(null, EntryType.CARBS);
        m.onValueChanged("15");
        m.onCloseRequested(ClosePath.BACK, true);
        assertEquals(Event.Kind.HIDE_IME, recorder.events.get(0).kind());
        assertFalse(m.state().discardDialog());
        assertEquals("15", m.state().valueText());
    }

    @Test
    public void saveLockBlocksCrossWritesAndMutationUntilFailureThenRetrySameId() {
        EntryFormModel m = edit();
        m.onSave();
        m.onSave();
        m.onDeleteRequested();
        m.onDeleteConfirmed();
        m.onValueChanged("2");
        assertEquals(1, writer.saves.size());
        assertTrue(writer.deletes.isEmpty());
        assertEquals("1.234", m.state().valueText());
        writer.saves.get(0).complete(Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT));
        assertTrue(m.dismissalEnabled());
        m.onSave();
        assertEquals(2, writer.saves.size());
        assertEquals("own", writer.saves.get(1).arg.identifier());
        assertEquals(Long.valueOf(42), writer.saves.get(1).arg.expectedSrvModifiedMs());
    }

    @Test
    public void deleteLockBlocksSaveCloseAndDuplicatesUntilOutcome() {
        EntryFormModel m = edit();
        m.onDeleteConfirmed();
        assertTrue(writer.deletes.isEmpty());
        m.onDeleteRequested();
        m.onDeleteConfirmed();
        m.onDeleteConfirmed();
        m.onSave();
        for (ClosePath path : ClosePath.values()) {
            m.onCloseRequested(path, false);
            assertFalse(m.state().discardDialog());
        }
        assertFalse(m.dismissalEnabled());
        assertTrue(recorder.events.isEmpty());
        assertTrue(writer.saves.isEmpty());
        assertEquals(1, writer.deletes.size());
        writer.deletes.get(0).complete(Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK));
        assertTrue(m.dismissalEnabled());
        assertEquals("1.234", m.state().valueText());
    }

    @Test
    public void liveRotationDoesNotRestoreAsDeathAndSuccessIsDeliveredOnce() {
        EntryFormModel m = open(null, EntryType.BG_CHECK);
        m.onValueChanged("123");
        m.onSave();
        m.start();
        m.onSave();
        assertEquals(1, writer.saves.size());
        assertEquals(EntryFormModel.Message.NONE, m.state().message());
        Result<WriteResult> success = Result.ok(new WriteResult(WriteKind.CREATED, m.identifier()));
        writer.saves.get(0).complete(success);
        writer.saves.get(0).complete(success);
        m.onCloseRequested();
        assertEquals(1, recorder.events.size());
        assertEquals(Event.Kind.FINISH, recorder.events.get(0).kind());
    }

    @Test
    public void restoredSaveAndDeleteNeverResendAndKeepWarningIdentifierAndDraft() {
        for (boolean delete : new boolean[] {false, true}) {
            store.map.clear();
            EntryFormModel m = edit();
            m.onValueChanged("1,234");
            if (delete) {
                m.onDeleteRequested();
                m.onDeleteConfirmed();
            } else {
                m.onSave();
            }
            int saves = writer.saves.size();
            int deletes = writer.deletes.size();
            m.clear();
            m = open("own", null);
            assertEquals("own", m.identifier());
            assertEquals("1,234", m.state().valueText());
            assertEquals(EntryFormModel.Message.UNSURE_SAVED, m.state().message());
            assertFalse(m.state().saveEnabled());
            assertFalse(m.state().deleteEnabled());
            assertTrue(m.dismissalEnabled());
            m.onSave();
            m.onDeleteRequested();
            m.onDeleteConfirmed();
            assertEquals(saves, writer.saves.size());
            assertEquals(deletes, writer.deletes.size());
        }
    }

    @Test
    public void pickerCancellationAndNowDoNotCommitTheIntermediateDate() {
        EntryFormModel m = open(null, EntryType.SENSOR_CHANGE);
        m.onDatePicked(clock.now.minusSeconds(86400).toEpochMilli());
        m.onPickerCancelled();
        assertNull(m.state().time());
        assertFalse(m.state().backGuard());
        m.onDatePicked(clock.now.minusSeconds(86400).toEpochMilli());
        m.onTimePicked(9, 30);
        Instant chosen = m.state().time();
        m.onDatePicked(clock.now.minus(Duration.ofDays(2)).toEpochMilli());
        m.onPickerCancelled();
        assertEquals(chosen, m.state().time());
        m.onTimeNow();
        assertFalse(m.state().backGuard());
        clock.now = clock.now.plusSeconds(60);
        m.onSave();
        assertEquals(clock.now, writer.saves.get(0).arg.time());
    }
}
