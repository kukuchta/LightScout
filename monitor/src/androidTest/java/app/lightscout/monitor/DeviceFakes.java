package app.lightscout.monitor;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.domain.status.TickReason;
import app.lightscout.domain.port.Clock;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.SyncCycle;
import app.lightscout.usecases.SyncResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fakes for the instrumented tests (stand-ins for CP-02 / CP-04 implementations; no Mockito — CP-00 §2).
 */
final class DeviceFakes {

    private DeviceFakes() {
    }

    /** IF-60 with in-memory DE flags; counts CE calls (must stay 0 — CP-00 §6). */
    static final class Flags implements AppFlagsStore {
        volatile boolean configured = true;
        volatile long handledBootCount;
        final AtomicInteger ceCalls = new AtomicInteger();

        @Override
        public boolean disclaimerAccepted() {
            ceCalls.incrementAndGet();
            return false;
        }

        @Override
        public void setDisclaimerAccepted() {
            ceCalls.incrementAndGet();
        }

        @Override
        public boolean onboardingCompleted() {
            ceCalls.incrementAndGet();
            return false;
        }

        @Override
        public void setOnboardingCompleted(boolean v) {
            ceCalls.incrementAndGet();
        }

        @Override
        public boolean monitoringConfigured() {
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
            handledBootCount = c;
        }
    }

    /** IF-66 recording logger. */
    static final class Log implements Logger {
        final List<String> lines = new CopyOnWriteArrayList<>();

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

        @Override
        public void debug(String tag, String message) {
            // not recorded
        }

        boolean contains(String line) {
            return lines.contains(line);
        }
    }

    /** IF-13 in memory. */
    static final class MonitorStore implements MonitorStateStore {
        volatile MonitorState state = MonitorState.initial();

        @Override
        public MonitorState get() {
            return state;
        }

        @Override
        public void put(MonitorState s) {
            state = s;
        }

        @Override
        public void clear() {
            state = MonitorState.initial();
        }
    }

    /** IF-14 in memory. */
    static final class AlarmStore implements AlarmStateStore {
        volatile AlarmState state = AlarmState.empty();

        @Override
        public AlarmState get() {
            return state;
        }

        @Override
        public void put(AlarmState s) {
            state = s;
        }

        @Override
        public void clear() {
            state = AlarmState.empty();
        }
    }

    /** IF-11 defaults. */
    static final class SettingsFake implements SettingsStore {
        @Override
        public Settings get() {
            return Settings.defaults();
        }

        @Override
        public void put(Settings s) {
            // unused
        }

        @Override
        public void addListener(Runnable onChange) {
            // unused
        }
    }

    /** IF-21: no snapshot yet (the placeholder handles {@code null}). */
    static final class Status implements StatusRepository {
        @Override
        public StatusSnapshot current() {
            return null;
        }

        @Override
        public void addListener(StatusListener l) {
            // unused
        }

        @Override
        public void removeListener(StatusListener l) {
            // unused
        }

        @Override
        public void refresh() {
            // unused
        }

        @Override
        public void retry() {
            // unused
        }
    }

    /** IF-20: records reasons; writes lastCheckCompleted like CP-02 (any outcome). */
    static final class Cycle implements SyncCycle {
        final List<TickReason> ticks = new CopyOnWriteArrayList<>();
        final AtomicInteger reevaluations = new AtomicInteger();
        final MonitorStore store;
        final Clock clock;

        Cycle(MonitorStore store, Clock clock) {
            this.store = store;
            this.clock = clock;
        }

        @Override
        public SyncResult runOnce(TickReason reason) {
            ticks.add(reason);
            Instant now = clock.now();
            MonitorState s = store.state;
            store.state = new MonitorState(s.monitoringSince() != null ? s.monitoringSince() : now,
                    s.lastSuccessfulCheck(), now, FetchOutcome.OK, s.connectionProblem(), now,
                    s.arrivalLagMedianSec(), s.recentLagSec(), s.lastDailyMaintenance(), s.cursors());
            return new SyncResult(FetchOutcome.OK, false, now, null, false, 60, 20);
        }

        @Override
        public SyncResult reevaluate() {
            reevaluations.incrementAndGet();
            return new SyncResult(FetchOutcome.OK, false, clock.now(), null, false, 60, 20);
        }

        boolean awaitTick(TickReason reason, long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                if (ticks.contains(reason)) {
                    return true;
                }
                Thread.sleep(100);
            }
            return ticks.contains(reason);
        }
    }
}
