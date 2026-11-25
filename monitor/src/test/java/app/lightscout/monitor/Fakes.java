package app.lightscout.monitor;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.domain.status.TickReason;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.ChannelState;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.usecases.SyncCycle;
import app.lightscout.usecases.SyncResult;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Hand-written fakes for the {@code :monitor} JVM tests (no Mockito — CP-00 §2).
 */
final class Fakes {

    static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    private Fakes() {
    }

    /** Wall + elapsed clock that advance together. */
    static final class FakeClock implements Clock {
        Instant now = T0;
        long elapsed = 1_000_000L;
        ZoneId zone = ZoneOffset.UTC;

        void advance(Duration d) {
            now = now.plus(d);
            elapsed += d.toMillis();
        }

        /** Wall clock jump without elapsed time (TIME_SET). */
        void jumpWall(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return elapsed;
        }

        @Override
        public ZoneId zone() {
            return zone;
        }
    }

    /** Records events as {@code CODE[:detail]}. */
    static final class RecordingLogger implements Logger {
        final List<String> lines = new ArrayList<>();

        @Override
        public void event(EventCode code) {
            lines.add(code.name());
        }

        @Override
        public void event(EventCode code, String detail) {
            lines.add(code.name() + ":" + detail);
        }

        @Override
        public void crash(String exceptionClass, String topAppFrame) {
            lines.add("CRASH:" + exceptionClass + "@" + topAppFrame);
        }

        /** Debug lines as {@code <tag> <message>}, kept apart from the event lines. */
        final List<String> debugLines = new ArrayList<>();

        @Override
        public void debug(String tag, String message) {
            debugLines.add(tag + " " + message);
        }

        long count(String prefix) {
            return lines.stream().filter(l -> l.startsWith(prefix)).count();
        }
    }

    /** Records alarm calls. */
    static final class FakeAlarmPort implements AlarmPort {
        boolean canExact = true;
        boolean throwOnExact;
        final List<String> calls = new ArrayList<>();
        final Map<Kind, Long> exact = new java.util.EnumMap<>(Kind.class);
        final Map<Kind, Long> inexact = new java.util.EnumMap<>(Kind.class);
        final Map<Kind, Long> alarmClock = new java.util.EnumMap<>(Kind.class);

        @Override
        public boolean canScheduleExact() {
            return canExact;
        }

        @Override
        public void setExactElapsed(Kind kind, long elapsedMs) {
            if (throwOnExact) {
                throw new SecurityException("exact refused");
            }
            clear(kind);
            exact.put(kind, elapsedMs);
            calls.add("exact:" + kind);
        }

        @Override
        public void setInexactElapsed(Kind kind, long elapsedMs) {
            clear(kind);
            inexact.put(kind, elapsedMs);
            calls.add("inexact:" + kind);
        }

        @Override
        public void setAlarmClock(Kind kind, long wallMs) {
            clear(kind);
            alarmClock.put(kind, wallMs);
            calls.add("alarmClock:" + kind);
        }

        @Override
        public void cancel(Kind kind) {
            clear(kind);
            calls.add("cancel:" + kind);
        }

        boolean armed(Kind kind) {
            return exact.containsKey(kind) || inexact.containsKey(kind) || alarmClock.containsKey(kind);
        }

        Long trigger(Kind kind) {
            if (exact.containsKey(kind)) {
                return exact.get(kind);
            }
            return inexact.get(kind);
        }

        private void clear(Kind kind) {
            exact.remove(kind);
            inexact.remove(kind);
            alarmClock.remove(kind);
        }
    }

    /** Records job net calls. */
    static final class FakeJobNet implements RuntimePorts.JobNet {
        boolean scheduled;
        int ensureCalls;

        @Override
        public void ensureScheduled() {
            ensureCalls++;
            scheduled = true;
        }

        @Override
        public void cancel() {
            scheduled = false;
        }
    }

    /** Records service starts. */
    static final class FakeService implements RuntimePorts.ServiceControl {
        boolean refuse;
        boolean running;
        final List<StartOrigin> starts = new ArrayList<>();

        @Override
        public boolean start(StartOrigin origin) {
            starts.add(origin);
            if (refuse) {
                return false;
            }
            running = true;
            return true;
        }

        @Override
        public void stop() {
            running = false;
        }
    }

    /** Wake lock that records holds and asserts balance. */
    static final class FakeWakeLock implements RuntimePorts.WakeLock {
        boolean held;
        int acquires;
        int releases;
        /** Whether the lock was held while the sync cycle ran. */
        boolean heldDuringRun;
        int refreshesWhileHeld;

        @Override
        public void acquire() {
            acquires++;
            held = true;
        }

        @Override
        public void refresh() {
            if (held) {
                refreshesWhileHeld++;
            }
        }

        @Override
        public void release() {
            releases++;
            held = false;
        }
    }

    /** Configurable sync cycle. */
    static final class FakeSyncCycle implements SyncCycle {
        Function<TickReason, SyncResult> onTick = r -> result(null, false);
        java.util.function.Supplier<SyncResult> onReevaluate = () -> result(null, false);
        final List<String> calls = new ArrayList<>();
        FakeWakeLock lock;
        Runnable sideEffect = () -> { };

        @Override
        public SyncResult runOnce(TickReason reason) {
            calls.add("tick:" + reason);
            if (lock != null) {
                lock.heldDuringRun = lock.held;
            }
            sideEffect.run();
            return onTick.apply(reason);
        }

        @Override
        public SyncResult reevaluate() {
            calls.add("reevaluate");
            return onReevaluate.get();
        }
    }

    static SyncResult result(Instant lastReading, boolean ringing) {
        return new SyncResult(FetchOutcome.OK, false, lastReading, null, ringing, 60, 20);
    }

    static SyncResult result(FetchOutcome outcome, Instant lastReading, Instant nextEvaluation, boolean ringing) {
        return new SyncResult(outcome, false, lastReading, nextEvaluation, ringing, 60, 20);
    }

    /** IF-13 fake; records puts (CP-08 must never write). */
    static final class FakeMonitorStateStore implements MonitorStateStore {
        MonitorState state = MonitorState.initial();
        boolean failGet;
        int puts;

        @Override
        public MonitorState get() {
            if (failGet) {
                throw new StorageException("monitor.get", new RuntimeException("x"));
            }
            return state;
        }

        @Override
        public void put(MonitorState s) {
            puts++;
            state = s;
        }

        @Override
        public void clear() {
            state = MonitorState.initial();
        }

        void setChecks(Instant monitoringSince, Instant lastCheckCompleted) {
            MonitorState i = MonitorState.initial();
            state = new MonitorState(monitoringSince, i.lastSuccessfulCheck(), lastCheckCompleted, i.lastOutcome(),
                    i.connectionProblem(), i.lastReadingTime(), i.arrivalLagMedianSec(), i.recentLagSec(),
                    i.lastDailyMaintenance(), i.cursors());
        }
    }

    /** IF-14 fake; records puts (CP-08 must never write). */
    static final class FakeAlarmStateStore implements AlarmStateStore {
        AlarmState state = AlarmState.empty();
        int puts;

        @Override
        public AlarmState get() {
            return state;
        }

        @Override
        public void put(AlarmState s) {
            puts++;
            state = s;
        }

        @Override
        public void clear() {
            state = AlarmState.empty();
        }

        void notRunningSnoozedUntil(Instant until) {
            state = new AlarmState(null, Map.of(AlarmType.NOT_RUNNING, new AlarmTypeState(null, until, 0, null)), null);
        }

        void ringing(AlarmType type) {
            state = new AlarmState(type, Map.of(type, new AlarmTypeState(T0, null, 0, null)), null);
        }
    }

    /** IF-11 fake. */
    static final class FakeSettingsStore implements SettingsStore {
        Settings settings = Settings.defaults();

        @Override
        public Settings get() {
            return settings;
        }

        @Override
        public void put(Settings s) {
            settings = s;
        }

        @Override
        public void addListener(Runnable onChange) {
            // unused
        }
    }

    /** IF-60 fake; CE methods fail the test if called (CP-00 §6). */
    static final class FakeFlags implements AppFlagsStore {
        boolean configured = true;
        boolean throwOnRead;
        long handledBootCount;
        int ceCalls;
        int bootCountWrites;

        @Override
        public boolean disclaimerAccepted() {
            ceCalls++;
            return true;
        }

        @Override
        public void setDisclaimerAccepted() {
            ceCalls++;
        }

        @Override
        public boolean onboardingCompleted() {
            ceCalls++;
            return true;
        }

        @Override
        public void setOnboardingCompleted(boolean v) {
            ceCalls++;
        }

        @Override
        public boolean monitoringConfigured() {
            if (throwOnRead) {
                throw new IllegalStateException("DE prefs unreadable");
            }
            return configured;
        }

        @Override
        public void setMonitoringConfigured(boolean v) {
            configured = v;
        }

        @Override
        public long handledBootCount() {
            return handledBootCount;
        }

        @Override
        public void setHandledBootCount(long c) {
            bootCountWrites++;
            handledBootCount = c;
        }
    }

    /** CMP-26 fake. */
    static final class FakeChannels implements NotificationChannels {
        int bypassChecks;
        int ensureCalls;

        @Override
        public void ensureChannels() {
            ensureCalls++;
        }

        @Override
        public ChannelState state(ChannelId id) {
            return new ChannelState(channelId(id), true, true, 4, true);
        }

        @Override
        public String channelId(ChannelId id) {
            return id.name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public void ensureAlarmChannelBypass() {
            bypassChecks++;
        }
    }

    /** Boot-count fake. */
    static final class FakeBootCount implements RuntimePorts.BootCountSource {
        long count = 7;

        @Override
        public long bootCount() {
            return count;
        }
    }

    /** Status snapshot with the fields the screen-on policy reads. */
    static StatusSnapshot snapshot(Reading latest, DataLossKind kind) {
        ConsumableStatus none = new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND, null, null,
                null, null);
        ConsumableStatus sensor = new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND, null, null,
                null, null);
        DataLossStatus dataLoss = kind == DataLossKind.DATA_LOSS
                ? new DataLossStatus(kind, app.lightscout.domain.dataloss.DataLossCause.UPLOADER, T0)
                : new DataLossStatus(kind, null, null);
        return new StatusSnapshot(latest, null, null, false, dataLoss,
                FetchOutcome.OK, null, null, null, true, false, true, true, true, true, 55, 70, 250, 20,
                null, null, none, sensor, 0, Banner.NONE, T0);
    }

    static Reading reading(Instant time) {
        return new Reading("r1", time, 120, Trend.FLAT, "test");
    }
}
