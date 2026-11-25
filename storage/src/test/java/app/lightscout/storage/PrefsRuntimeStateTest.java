package app.lightscout.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.StorageException;

/**
 * IF-13 / IF-14 / IF-63 / IF-64 over the ST-04 {@code runtime} file: TST-05-18 round trips, TST-05-07 clear
 * scope (R-22: ENT-07…ENT-10 only, ENT-11 flags kept), TST-05-06 ENT-10 trim, R-20 failures.
 */
public class PrefsRuntimeStateTest {

    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    private final FakeSharedPreferences prefs = new FakeSharedPreferences();
    private final RecordingLogger logger = new RecordingLogger();
    private final StoreGuard guard = new StoreGuard(logger);
    private final PrefsMonitorStateStore monitor = new PrefsMonitorStateStore(prefs, new CacheGeneration(prefs), guard);
    private final PrefsAlarmStateStore alarm = new PrefsAlarmStateStore(prefs, guard);
    private final PrefsReminderStateStore reminder = new PrefsReminderStateStore(prefs, guard);
    private final PrefsNotifiedAlertStore notified = new PrefsNotifiedAlertStore(prefs, guard);

    private static Instant t(long minutes) {
        return T0.plusSeconds(minutes * 60);
    }

    static MonitorState fullMonitorState() {
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(NsCollection.class);
        cursors.put(NsCollection.ENTRIES, new CollectionCursor(1_700_000_000_123L, t(-60), t(-30), t(-44_640), true,
                1_700_000_000_999L));
        cursors.put(NsCollection.TREATMENTS, new CollectionCursor(null, null, null, null, false, null));
        cursors.put(NsCollection.DEVICESTATUS, new CollectionCursor(5L, t(-5), null, t(-1440), false, null));
        cursors.put(NsCollection.PROFILE, new CollectionCursor(null, null, t(-2), null, true, 42L));
        return new MonitorState(t(-600), t(-1), t(0), FetchOutcome.NS_NOT_RESPONDING, FetchOutcome.SERVER_TOO_OLD,
                t(-4), 75, List.of(60, 45, 120, 15), t(-300), cursors);
    }

    static AlarmState fullAlarmState() {
        Map<AlarmType, AlarmTypeState> perType = new EnumMap<>(AlarmType.class);
        perType.put(AlarmType.URGENT_LOW, new AlarmTypeState(t(-3), null, 2, t(-4)));
        perType.put(AlarmType.LOW, new AlarmTypeState(null, t(15), 0, null));
        perType.put(AlarmType.NO_DATA, new AlarmTypeState(null, t(30), 0, null));
        perType.put(AlarmType.NOT_RUNNING, new AlarmTypeState(null, t(31), 1, null));
        perType.put(AlarmType.HIGH, new AlarmTypeState(null, t(60), 0, t(-10)));
        return new AlarmState(AlarmType.URGENT_LOW, perType, 9);
    }

    // ---------------------------------------------------------------- IF-13

    @Test
    public void monitorStateIsInitialWhenNothingWasStored() {
        assertEquals(MonitorState.initial(), monitor.get());
    }

    @Test
    public void monitorStateRoundTripsAllFieldsIncludingCursorsAndLastCheckCompleted() {
        MonitorState s = fullMonitorState();
        monitor.get();      // the single writer always reads before it writes (R-21 get → put)
        monitor.put(s);
        MonitorState read = monitor.get();
        assertEquals(s, read);
        assertEquals(t(0), read.lastCheckCompleted());
        assertEquals(t(-44_640), read.cursors().get(NsCollection.ENTRIES).completeFrom());
        assertTrue(read.cursors().get(NsCollection.ENTRIES).historyCapable());
        assertEquals(0, prefs.applies);
    }

    @Test
    public void monitorStateInitialRoundTrips() {
        monitor.put(MonitorState.initial());
        assertEquals(MonitorState.initial(), monitor.get());
    }

    @Test
    public void lagSamplesWrittenBeforeTheServerTimeEstimatorAreDroppedAndNewOnesSurvive() {
        // D-152: the old samples were "tick start - reading time", pinned at the 180 s clamp by the schedule
        prefs.values.put(PrefsMonitorStateStore.KEY,
                "{\"lastOutcome\":\"OK\",\"arrivalLagMedianSec\":180,\"recentLagSec\":[203,203,165],\"cursors\":{}}");
        MonitorState old = monitor.get();
        assertEquals(60, old.arrivalLagMedianSec());
        assertTrue(old.recentLagSec().isEmpty());
        MonitorState fresh = new MonitorState(null, null, null, FetchOutcome.OK, null, null, 38,
                java.util.List.of(34, 38, 41), null, java.util.Map.of());
        monitor.put(fresh);
        MonitorState read = monitor.get();
        assertEquals(38, read.arrivalLagMedianSec());
        assertEquals(java.util.List.of(34, 38, 41), read.recentLagSec());
    }

    @Test
    public void monitorStateFromLaterVersionIgnoresUnknownKeysAndDefaultsMissingOnes() {
        prefs.values.put(PrefsMonitorStateStore.KEY,
                "{\"lastOutcome\":\"SOME_FUTURE_OUTCOME\",\"newField\":1,\"cursors\":{\"FUTURE\":{}}}");
        MonitorState read = monitor.get();
        assertEquals(FetchOutcome.OK, read.lastOutcome());
        assertEquals(60, read.arrivalLagMedianSec());
        assertTrue(read.cursors().isEmpty());
        assertNull(read.lastCheckCompleted());
    }

    @Test
    public void corruptMonitorStateIsLoggedAndReadAsInitialThenOverwritten() {
        prefs.values.put(PrefsMonitorStateStore.KEY, "{broken");
        assertEquals(MonitorState.initial(), monitor.get());
        assertEquals(List.of("STORAGE_ERROR:monitor_state.get"), logger.lines);
        monitor.put(fullMonitorState());
        assertEquals(fullMonitorState(), monitor.get());
    }

    @Test
    public void monitorStateOfTheWrongPreferenceTypeIsReadAsInitial() {
        prefs.values.put(PrefsMonitorStateStore.KEY, 42);
        assertEquals(MonitorState.initial(), monitor.get());
        assertEquals(List.of("STORAGE_ERROR:monitor_state.get"), logger.lines);
    }

    @Test
    public void monitorStateReadFailureStillThrows() {
        prefs.failReads = true;
        StorageException e = assertThrows(StorageException.class, monitor::get);
        assertEquals("monitor_state.get", e.op());
    }

    @Test
    public void cacheRecreationResetsCursorsButKeepsTheOtherFields() {
        CacheGeneration generation = new CacheGeneration(prefs);
        monitor.get();
        monitor.put(fullMonitorState());
        assertEquals(fullMonitorState(), monitor.get());

        generation.bump();
        MonitorState read = monitor.get();
        assertTrue(read.cursors().isEmpty());
        assertEquals(fullMonitorState().lastCheckCompleted(), read.lastCheckCompleted());
        assertEquals(fullMonitorState().lastReadingTime(), read.lastReadingTime());
        assertEquals(fullMonitorState().recentLagSec(), read.recentLagSec());

        monitor.put(fullMonitorState());
        assertEquals("cursors written after the re-creation are kept", fullMonitorState(), monitor.get());
    }

    @Test
    public void recreationBetweenGetAndPutStillResetsTheCursors() {
        CacheGeneration generation = new CacheGeneration(prefs);
        MonitorState before = monitor.get();
        generation.bump();
        monitor.put(fullMonitorState());
        assertTrue(monitor.get().cursors().isEmpty());
        assertEquals(MonitorState.initial(), before);
    }

    @Test
    public void putWithoutAPriorGetNeverAdoptsTheCurrentGeneration() throws Exception {
        CacheGeneration generation = new CacheGeneration(prefs);
        generation.bump();
        Thread writer = new Thread(() -> monitor.put(fullMonitorState()));
        writer.start();
        writer.join();
        assertTrue("no stored generation: UNREADABLE, reset kept", monitor.get().cursors().isEmpty());
    }

    @Test
    public void putWithoutAPriorGetKeepsTheStoredGeneration() throws Exception {
        CacheGeneration generation = new CacheGeneration(prefs);
        generation.bump();
        monitor.get();
        monitor.put(fullMonitorState());
        Thread writer = new Thread(() -> monitor.put(fullMonitorState()));
        writer.start();
        writer.join();
        assertEquals(1L, prefs.values.get(PrefsMonitorStateStore.CURSORS_GENERATION_KEY));
        assertEquals(fullMonitorState(), monitor.get());
    }

    @Test
    public void theGenerationSeenAtGetIsUsedByOnePutOnly() {
        CacheGeneration generation = new CacheGeneration(prefs);
        monitor.get();                        // sees generation 0
        monitor.put(fullMonitorState());      // records 0, forgets the thread-local
        generation.bump();                    // cache re-created
        monitor.put(fullMonitorState());      // no get since: keeps stored 0, never adopts 1
        assertTrue(monitor.get().cursors().isEmpty());
    }

    @Test
    public void clearKeepsTheCacheGeneration() {
        new CacheGeneration(prefs).bump();
        monitor.put(fullMonitorState());
        monitor.clear();
        assertEquals(1L, prefs.values.get(CacheGeneration.KEY));
        assertFalse(prefs.values.containsKey(PrefsMonitorStateStore.CURSORS_GENERATION_KEY));
    }

    @Test
    public void monitorStatePutFailureFailsWithStorageException() {
        prefs.failCommits = true;
        assertThrows(StorageException.class, () -> monitor.put(fullMonitorState()));
        assertEquals(List.of("STORAGE_ERROR:monitor_state.put"), logger.lines);
    }

    // ---------------------------------------------------------------- IF-14

    @Test
    public void alarmStateIsEmptyWhenNothingWasStored() {
        assertEquals(AlarmState.empty(), alarm.get());
    }

    @Test
    public void alarmStateRoundTripsActiveTypeVolumeAndPerTypeSnoozeTimes() {
        AlarmState s = fullAlarmState();
        alarm.put(s);
        AlarmState read = alarm.get();
        assertEquals(s, read);
        assertEquals(Integer.valueOf(9), read.savedUserAlarmVolume());
        assertEquals(AlarmType.URGENT_LOW, read.activeType());
        assertEquals(t(60), read.perType().get(AlarmType.HIGH).snoozedUntil());
    }

    @Test
    public void alarmStateRoundTripsANullVolumeAndNoActiveType() {
        AlarmState s = new AlarmState(null, Map.of(AlarmType.LOW, new AlarmTypeState(null, t(15), 0, null)), null);
        alarm.put(s);
        assertEquals(s, alarm.get());
        assertNull(alarm.get().savedUserAlarmVolume());
    }

    @Test
    public void corruptOrWrongTypeAlarmStateIsLoggedAndReadAsEmpty() {
        prefs.values.put(PrefsAlarmStateStore.KEY, "[not an object");
        assertEquals(AlarmState.empty(), alarm.get());
        prefs.values.put(PrefsAlarmStateStore.KEY, 7L);
        assertEquals(AlarmState.empty(), alarm.get());
        assertEquals(List.of("STORAGE_ERROR:alarm_state.get", "STORAGE_ERROR:alarm_state.get"), logger.lines);
        alarm.put(fullAlarmState());
        assertEquals(fullAlarmState(), alarm.get());
    }

    @Test
    public void alarmStateReadFailureStillThrows() {
        prefs.failReads = true;
        assertThrows(StorageException.class, alarm::get);
    }

    @Test
    public void alarmStatePutFailureFailsWithStorageException() {
        prefs.failCommits = true;
        assertThrows(StorageException.class, () -> alarm.put(fullAlarmState()));
        assertEquals(List.of("STORAGE_ERROR:alarm_state.put"), logger.lines);
    }

    // ---------------------------------------------------------------- IF-63

    @Test
    public void reminderStateRoundTripsPerKind() {
        ReminderState site = new ReminderState(ConsumableKind.SITE, "site-1", t(100), t(1540), true, false);
        ReminderState sensor = new ReminderState(ConsumableKind.SENSOR, "sensor-'1;--", t(200), t(1640), false, true);
        assertEquals(Optional.empty(), reminder.get(ConsumableKind.SITE));
        reminder.put(site);
        reminder.put(sensor);
        assertEquals(Optional.of(site), reminder.get(ConsumableKind.SITE));
        assertEquals(Optional.of(sensor), reminder.get(ConsumableKind.SENSOR));
    }

    @Test
    public void corruptIncompleteOrWrongTypeReminderStateIsLoggedAndReadAsEmpty() {
        prefs.values.put(PrefsReminderStateStore.KEY_SITE, "{broken");
        prefs.values.put(PrefsReminderStateStore.KEY_SENSOR, "{\"due24hAt\":1}");
        assertEquals(Optional.empty(), reminder.get(ConsumableKind.SITE));
        assertEquals(Optional.empty(), reminder.get(ConsumableKind.SENSOR));
        prefs.values.put(PrefsReminderStateStore.KEY_SITE, true);
        assertEquals(Optional.empty(), reminder.get(ConsumableKind.SITE));
        assertEquals(3, logger.lines.size());
        assertTrue(logger.lines.stream().allMatch("STORAGE_ERROR:reminder_state.get"::equals));
    }

    @Test
    public void reminderStateReadFailureStillThrows() {
        prefs.failReads = true;
        assertThrows(StorageException.class, () -> reminder.get(ConsumableKind.SITE));
    }

    // ---------------------------------------------------------------- IF-64

    @Test
    public void notifiedAlertsAreAddedAndFound() {
        assertFalse(notified.contains("a1"));
        notified.add("a1", t(0));
        notified.add("id with ' ; -- chars", t(1));
        assertTrue(notified.contains("a1"));
        assertTrue(notified.contains("id with ' ; -- chars"));
        assertFalse(notified.contains("a2"));
    }

    @Test
    public void notifiedAlertsAreTrimmedToTheCacheWindow() {
        notified.add("old", t(-44_641));
        notified.add("edge", t(-44_640));
        notified.add("new", t(0));
        prefs.values.put(PrefsAppFlagsStore.ONBOARDING_COMPLETED, true);
        notified.evictBefore(t(-44_640));
        assertFalse(notified.contains("old"));
        assertTrue(notified.contains("edge"));
        assertTrue(notified.contains("new"));
        assertEquals(true, prefs.values.get(PrefsAppFlagsStore.ONBOARDING_COMPLETED));
    }

    @Test
    public void notifiedAlertsUseOneConstantKeyNeverTheEntryId() {
        notified.add("a1", t(0));
        notified.add("id with ' ; -- chars", t(1));
        assertEquals(java.util.Set.of(PrefsNotifiedAlertStore.KEY, PrefsSchema.KEY), prefs.values.keySet());
    }

    @Test
    public void corruptOrWrongTypeNotifiedAlertsAreLoggedAndReadAsEmpty() {
        prefs.values.put(PrefsNotifiedAlertStore.KEY, "{broken");
        assertFalse(notified.contains("a1"));
        prefs.values.put(PrefsNotifiedAlertStore.KEY, 5);
        assertFalse(notified.contains("a1"));
        notified.add("a1", t(0));
        assertTrue(notified.contains("a1"));
        assertEquals(List.of("STORAGE_ERROR:notified_alert.contains", "STORAGE_ERROR:notified_alert.contains",
                "STORAGE_ERROR:notified_alert.add"), logger.lines);
    }

    @Test
    public void notifiedAlertsReadFailureStillThrows() {
        prefs.failReads = true;
        assertThrows(StorageException.class, () -> notified.contains("a1"));
    }

    // ---------------------------------------------------------------- clear scope (R-22, R-71)

    @Test
    public void clearingEnt07To10LeavesNoStateButKeepsTheEnt11Flags() {
        prefs.values.put(PrefsAppFlagsStore.DISCLAIMER_ACCEPTED, true);
        prefs.values.put(PrefsAppFlagsStore.ONBOARDING_COMPLETED, true);
        monitor.put(fullMonitorState());
        alarm.put(fullAlarmState());
        reminder.put(new ReminderState(ConsumableKind.SITE, "s", t(1), t(2), false, false));
        reminder.put(new ReminderState(ConsumableKind.SENSOR, "n", t(1), t(2), false, false));
        notified.add("a1", t(0));
        PrefsSupportPromptStore supportPrompt = new PrefsSupportPromptStore(prefs, guard);
        supportPrompt.firstUse(t(0));
        supportPrompt.markShown();

        monitor.clear();
        alarm.clear();
        reminder.clear();
        notified.clear();

        assertEquals(MonitorState.initial(), monitor.get());
        assertEquals(AlarmState.empty(), alarm.get());
        assertEquals(Optional.empty(), reminder.get(ConsumableKind.SITE));
        assertEquals(Optional.empty(), reminder.get(ConsumableKind.SENSOR));
        assertFalse(notified.contains("a1"));
        // DCR-46: the support prompt state survives a server change (a reconnect does not bring the prompt back)
        assertEquals(Map.of(PrefsAppFlagsStore.DISCLAIMER_ACCEPTED, true,
                PrefsAppFlagsStore.ONBOARDING_COMPLETED, true,
                PrefsSupportPromptStore.KEY_FIRST_USE_MS, t(0).toEpochMilli(),
                PrefsSupportPromptStore.KEY_SHOWN, true, PrefsSchema.KEY, 1), prefs.values);
    }

    @Test
    public void clearFailureFailsWithStorageException() {
        prefs.failCommits = true;
        assertThrows(StorageException.class, monitor::clear);
        assertThrows(StorageException.class, alarm::clear);
        assertThrows(StorageException.class, reminder::clear);
        assertThrows(StorageException.class, notified::clear);
        assertEquals(List.of("STORAGE_ERROR:monitor_state.clear", "STORAGE_ERROR:alarm_state.clear",
                "STORAGE_ERROR:reminder_state.clear", "STORAGE_ERROR:notified_alert.clear"), logger.lines);
    }
}
