package app.lightscout.usecases.fakes;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.SyncResult;
import app.lightscout.usecases.alarm.DefaultAlarmActions;
import app.lightscout.usecases.status.DefaultStatusRepository;
import app.lightscout.usecases.status.StatusProjector;
import app.lightscout.usecases.sync.DefaultSyncCycle;
import app.lightscout.usecases.sync.SyncPorts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Wires the CP-02a use cases to fresh fakes. Default: a steady, already filled cache (no backfill, no daily run). */
public final class Harness {

    public static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    /** lastModified values of the steady state. */
    public static final long LM_ENTRIES = 1_000L;
    public static final long LM_TREATMENTS = 2_000L;
    public static final long LM_DEVICESTATUS = 3_000L;
    public static final long LM_PROFILE = 4_000L;

    public final Fakes.FakeClock clock = new Fakes.FakeClock(NOW);
    public final Fakes.FakeGateway gateway = new Fakes.FakeGateway();
    public final Fakes.FakeConnectionStore connection = new Fakes.FakeConnectionStore();
    public final Fakes.FakeReadingStore readings = new Fakes.FakeReadingStore();
    public final Fakes.FakeEntryStore entries = new Fakes.FakeEntryStore();
    public final Fakes.FakeDeviceStatusStore deviceStatus = new Fakes.FakeDeviceStatusStore();
    public final Fakes.FakeSettingsStore settings = new Fakes.FakeSettingsStore();
    public final Fakes.FakeMonitorStateStore monitorState = new Fakes.FakeMonitorStateStore();
    public final Fakes.FakeAlarmStateStore alarmState = new Fakes.FakeAlarmStateStore();
    public final Fakes.FakeReminderStateStore reminderState = new Fakes.FakeReminderStateStore();
    public final Fakes.FakeNotifiedAlertStore notified = new Fakes.FakeNotifiedAlertStore();
    public final Fakes.FakeAlarmController alarmController = new Fakes.FakeAlarmController();
    public final Fakes.FakeReminderScheduler reminders = new Fakes.FakeReminderScheduler();
    public final Fakes.FakePumpAlertNotifier pumpAlerts = new Fakes.FakePumpAlertNotifier();
    public final Fakes.FakeConnectivity connectivity = new Fakes.FakeConnectivity();
    public final Fakes.FakeReadiness readiness = new Fakes.FakeReadiness();
    public final Fakes.FakeAppFlags appFlags = new Fakes.FakeAppFlags();
    public final Fakes.FakeLogger logger = new Fakes.FakeLogger();
    public final Fakes.FakeMonitorControl monitorControl = new Fakes.FakeMonitorControl();
    public final Fakes.QueueExecutor monitorExecutor = new Fakes.QueueExecutor();
    public final List<StatusSnapshot> snapshots = new ArrayList<>();

    public final app.lightscout.usecases.alarm.AlarmStateMemory alarmMemory =
            new app.lightscout.usecases.alarm.AlarmStateMemory();
    public final app.lightscout.usecases.common.MonitorStateMemory monitorMemory =
            new app.lightscout.usecases.common.MonitorStateMemory();
    /** Shared ST-02 writer (IR-44). */
    public final app.lightscout.usecases.common.SettingsWriter settingsWriter;
    public final StatusProjector projector;
    public final DefaultStatusRepository status;
    public final DefaultSyncCycle cycle;
    public final DefaultAlarmActions actions;

    public Harness() {
        monitorState.value = steadyState(NOW);
        settingsWriter = new app.lightscout.usecases.common.SettingsWriter(settings);
        gateway.lastModified = () -> app.lightscout.domain.port.Result.ok(steadyLastModified());
        projector = new StatusProjector(readings, entries, deviceStatus, monitorState, settings, alarmState,
                connectivity, readiness, appFlags, clock, alarmMemory, monitorMemory, logger);
        status = new DefaultStatusRepository(projector, connectivity, settings, monitorExecutor, monitorControl,
                clock);
        status.addListener(snapshots::add);
        cycle = new DefaultSyncCycle(new SyncPorts(gateway, connection, readings, entries, deviceStatus, settings,
                monitorState, alarmState, reminderState, notified, alarmController, reminders, pumpAlerts, clock,
                logger), projector, status, alarmMemory, monitorMemory);
        actions = new DefaultAlarmActions(monitorExecutor, settingsWriter, alarmState, alarmMemory, alarmController, cycle,
                monitorControl, clock, logger);
    }

    public static Map<NsCollection, Long> steadyLastModified() {
        Map<NsCollection, Long> m = new EnumMap<>(NsCollection.class);
        m.put(NsCollection.ENTRIES, LM_ENTRIES);
        m.put(NsCollection.TREATMENTS, LM_TREATMENTS);
        m.put(NsCollection.DEVICESTATUS, LM_DEVICESTATUS);
        m.put(NsCollection.PROFILE, LM_PROFILE);
        return m;
    }

    /** Monitoring running for 2 days, cache fully backfilled, daily maintenance done today, last reading 3 min ago. */
    public static MonitorState steadyState(Instant now) {
        Instant complete = now.minus(Duration.ofDays(40));
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(NsCollection.class);
        cursors.put(NsCollection.ENTRIES, new CollectionCursor(LM_ENTRIES, null, now, complete, false, null));
        cursors.put(NsCollection.TREATMENTS, new CollectionCursor(LM_TREATMENTS, now.minus(Duration.ofHours(48)),
                now, complete, false, null));
        cursors.put(NsCollection.DEVICESTATUS, new CollectionCursor(LM_DEVICESTATUS, null, null, null, false, null));
        cursors.put(NsCollection.PROFILE, new CollectionCursor(LM_PROFILE, null, null, null, false, null));
        return new MonitorState(now.minus(Duration.ofDays(2)), now.minusSeconds(60), now.minusSeconds(60),
                FetchOutcome.OK, null, now.minusSeconds(180), 60, List.of(), now.minusSeconds(60), cursors);
    }

    /** Replaces one collection's lastModified value in the gateway answer. */
    public void lastModified(NsCollection c, long value) {
        Map<NsCollection, Long> m = steadyLastModified();
        m.put(c, value);
        gateway.lastModified = () -> app.lightscout.domain.port.Result.ok(m);
    }

    /** Elapsed time since boot used by {@link #windowCallCostsMs}: far after any test instant, so no boot rule applies. */
    private static final long BOOT_ELAPSED_MS = 10_000_000_000L;

    /**
     * DCR-45 test aid: every gateway window call costs {@code costMs} of elapsed time, so the 15 s backfill budget
     * buys a known number of slices (with 15 000 ms a collection gets exactly one slice per tick).
     */
    public void windowCallCostsMs(long costMs) {
        clock.elapsedSinceBootMs = BOOT_ELAPSED_MS;
        gateway.afterWindowCall = () -> clock.elapsedSinceBootMs += costMs;
    }

    public SyncResult tick() {
        return cycle.runOnce(TickReason.SCHEDULED);
    }

    public SyncResult tick(TickReason reason) {
        return cycle.runOnce(reason);
    }

    public MonitorState state() {
        return monitorState.value;
    }

    public CollectionCursor cursor(NsCollection c) {
        return monitorState.value.cursors().get(c);
    }
}
