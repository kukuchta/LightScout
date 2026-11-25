package app.lightscout.monitor;

import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.status.TickReason;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * IF-62 {@link MonitorControl} implementation (CP-08 §4.1, CP-00A R-32). {@code requestTick} and
 * {@code reevaluateAlarms} enqueue on {@code monitorExecutor} and return immediately; the single-thread executor
 * serialises concurrent calls (03 §6.4). {@code startMonitoring} starts {@code MonitorService} (idempotent) from
 * the caller's foreground context; the caller then requests its tick ({@code ONBOARDING_DONE} / {@code APP_OPEN}).
 */
final class DefaultMonitorControl implements MonitorControl {

    private final Executor monitorExecutor;
    private final TickRunner runner;
    private final ServiceLauncher launcher;

    /**
     * Creates the control.
     *
     * @param monitorExecutor the single-thread executor (R-34)
     * @param runner          tick runner
     * @param launcher        service launcher
     */
    DefaultMonitorControl(Executor monitorExecutor, TickRunner runner, ServiceLauncher launcher) {
        this.monitorExecutor = Objects.requireNonNull(monitorExecutor, "monitorExecutor");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public void requestTick(TickReason r) {
        Objects.requireNonNull(r, "r");
        monitorExecutor.execute(() -> runner.runTick(r));
    }

    @Override
    public void reevaluateAlarms() {
        monitorExecutor.execute(runner::runReevaluate);
    }

    @Override
    public void startMonitoring() {
        launcher.launch(StartOrigin.ACTIVITY);
    }

    /**
     * Last known ringing state (for the service's refusal retry, IR-72).
     *
     * @return {@code true} if the last evaluation reported a ringing alarm
     */
    boolean lastKnownRinging() {
        return runner.lastKnownRinging();
    }

    /**
     * Enqueues the work of a service start (dispatch of {@link StartDispatch#work(StartOrigin)}).
     *
     * @param origin start origin
     */
    void dispatch(StartOrigin origin) {
        switch (StartDispatch.work(origin)) {
            case TICK -> requestTick(StartDispatch.reason(origin));
            case WATCHDOG -> monitorExecutor.execute(runner::runWatchdog);
            case HEARTBEAT -> monitorExecutor.execute(runner::runHeartbeat);
            case NONE -> {
                // startMonitoring(): the caller requests its own tick (R-32)
            }
        }
    }
}
