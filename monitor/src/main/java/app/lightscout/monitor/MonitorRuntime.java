package app.lightscout.monitor;

import android.content.Context;

import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.domain.status.TickReason;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.SyncCycle;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Process-wide host of CP-08 (MOD-08 slice A), built **once per process after the first unlock** by the
 * {@code :app} composition root (CP-15) and returned by {@link MonitorRuntimeProvider#monitorRuntime()}.
 *
 * <p>It wires the IF-62 implementation ({@link #control()}), the tick runner on {@code monitorExecutor}, the
 * alarm / job / service adapters and the IF-19 connectivity trigger (registered once here; IF-19 has no remove).
 * {@code :monitor} never constructs {@code :storage} / {@code :nightscout} classes: every store, the sync cycle and
 * the platform services arrive in {@link Collaborators}.
 */
public final class MonitorRuntime {

    /**
     * Collaborators injected by the composition root (R-6, R-34).
     *
     * @param syncCycle       IF-20, resolved lazily on {@code monitorExecutor} (it needs IF-21, which itself needs
     *                        {@link #control()} — the container builds them after this runtime)
     * @param status          IF-21, resolved lazily (same construction cycle)
     * @param monitorState    IF-13 (read only in {@code :monitor})
     * @param alarmState      IF-14 (read only in {@code :monitor})
     * @param settings        IF-11 (read only)
     * @param flags           IF-60
     * @param channels        CMP-26
     * @param connectivity    IF-19 — the single process-wide {@code AndroidConnectivity}
     * @param clock           IF-15
     * @param logger          IF-66
     * @param monitorExecutor the single-thread executor shared with {@code :usecases} and {@code :alarm}
     * @param history         IF-22, resolved lazily; feeds the S-10 chart (DCR-37) and may return {@code null}
     */
    public record Collaborators(Supplier<SyncCycle> syncCycle, Supplier<StatusRepository> status,
                                MonitorStateStore monitorState, AlarmStateStore alarmState, SettingsStore settings,
                                AppFlagsStore flags, NotificationChannels channels, Connectivity connectivity,
                                Clock clock, Logger logger, Executor monitorExecutor,
                                Supplier<HistoryLoader> history) {

        /** Creates the bundle without a history source: S-10 then has no chart. */
        public Collaborators(Supplier<SyncCycle> syncCycle, Supplier<StatusRepository> status,
                             MonitorStateStore monitorState, AlarmStateStore alarmState, SettingsStore settings,
                             AppFlagsStore flags, NotificationChannels channels, Connectivity connectivity,
                             Clock clock, Logger logger, Executor monitorExecutor) {
            this(syncCycle, status, monitorState, alarmState, settings, flags, channels, connectivity, clock,
                    logger, monitorExecutor, () -> null);
        }

        /**
         * Creates the bundle.
         *
         * @throws NullPointerException if a collaborator is null
         */
        public Collaborators {
            Objects.requireNonNull(syncCycle, "syncCycle");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(monitorState, "monitorState");
            Objects.requireNonNull(alarmState, "alarmState");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(flags, "flags");
            Objects.requireNonNull(channels, "channels");
            Objects.requireNonNull(connectivity, "connectivity");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(logger, "logger");
            Objects.requireNonNull(monitorExecutor, "monitorExecutor");
            Objects.requireNonNull(history, "history");
        }
    }

    private final Collaborators collaborators;
    private final DefaultMonitorControl control;
    private final ScreenOnPolicy screenOnPolicy = new ScreenOnPolicy();

    /**
     * Builds the runtime and registers the connectivity trigger.
     *
     * @param context       any context (the application context is used)
     * @param collaborators collaborators
     */
    public MonitorRuntime(Context context, Collaborators collaborators) {
        this.collaborators = Objects.requireNonNull(collaborators, "collaborators");
        Context app = context.getApplicationContext();
        MonitorAlarms alarms = new MonitorAlarms(new AndroidAlarmPort(app), collaborators.clock(),
                collaborators.logger(), MonitorConfig.ALARM_CLOCK_FOR_EXPECTED_READING_TICK);
        ServiceLauncher launcher = new ServiceLauncher(new AndroidServiceStarter(app), alarms, collaborators.logger());
        TickRunner runner = new TickRunner(collaborators.syncCycle(), collaborators.monitorState(),
                collaborators.alarmState(), collaborators.settings(), collaborators.flags(), collaborators.channels(),
                alarms, new AndroidJobNet(app), launcher, TickWakeLock.forTick(app), new SettingsBootCountSource(app),
                BootNotHandledGuard.PROCESS,
                collaborators.clock(), collaborators.logger());
        this.control = new DefaultMonitorControl(collaborators.monitorExecutor(), runner, launcher);
        collaborators.connectivity().addListener(
                new ConnectivityTrigger(control, collaborators.connectivity().isOnline()));
    }

    /**
     * Returns the IF-62 implementation (register it in the container as {@link MonitorControl}).
     *
     * @return IF-62
     */
    public MonitorControl control() {
        return control;
    }

    /**
     * Enqueues the work of a service start ({@link StartDispatch}).
     *
     * @param origin origin
     */
    void onServiceStarted(StartOrigin origin) {
        control.dispatch(origin);
    }

    /**
     * Last known ringing state of this process.
     *
     * @return {@code true} if the last evaluation reported a ringing alarm
     */
    boolean lastKnownRinging() {
        return control.lastKnownRinging();
    }

    /**
     * Returns the current snapshot (memory).
     *
     * @return snapshot (may be null before the first projection)
     */
    StatusSnapshot currentSnapshot() {
        try {
            return collaborators.status().get().current();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns the process status repository for the S-10 updater (IF-21).
     *
     * @return IF-21
     */
    app.lightscout.usecases.StatusRepository statusRepository() {
        return collaborators.status().get();
    }

    /**
     * Returns the history source of the S-10 chart.
     *
     * @return IF-22, or {@code null} when the composition root has none
     */
    HistoryLoader historyLoader() {
        return collaborators.history().get();
    }

    /**
     * {@code SCREEN_ON} (CP-00A R-37): stale data → immediate {@code SCREEN_ON_STALE} tick.
     */
    void onScreenOn() {
        if (screenOnPolicy.shouldTick(currentSnapshot(), collaborators.clock().now())) {
            control.requestTick(TickReason.SCREEN_ON_STALE);
        }
    }

    /**
     * Returns the collaborators (for the service).
     *
     * @return collaborators
     */
    Collaborators collaborators() {
        return collaborators;
    }
}
