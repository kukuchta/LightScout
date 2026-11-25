package app.lightscout;

import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.status.TickReason;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The APP_OPEN path of {@code MainActivity.onStart} (CP-00A R-86, SS-09 §5.5–5.6, Q-67):
 * <ol>
 *   <li>on {@code ioExecutor}: read {@code onboardingCompleted} (CE prefs — never on the main thread);</li>
 *   <li>if completed, back on the main thread and only while the activity is still started (a foreground-allowed
 *       context for the FGS start, R-32): {@code startMonitoring()} then {@code requestTick(APP_OPEN)};</li>
 *   <li>then the F5 boot-count check on {@code ioExecutor} (CP-08 {@code BootCountCheck}).</li>
 * </ol>
 * Before onboarding is completed nothing is started (monitoring starts from S-07 „Done” through IF-27).
 */
final class AppOpenFlow {

    private final Executor io;
    private final Executor main;
    private final AppFlagsStore flags;
    private final Supplier<MonitorControl> control;
    private final Supplier<Runnable> bootCountCheck;

    /**
     * Creates the flow.
     *
     * @param io             {@code ioExecutor}
     * @param main           the main-thread executor
     * @param flags          IF-60
     * @param control        IF-62, resolved on the main thread when needed
     * @param bootCountCheck creates the F5 check task
     */
    AppOpenFlow(Executor io, Executor main, AppFlagsStore flags, Supplier<MonitorControl> control,
                Supplier<Runnable> bootCountCheck) {
        this.io = Objects.requireNonNull(io, "io");
        this.main = Objects.requireNonNull(main, "main");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.control = Objects.requireNonNull(control, "control");
        this.bootCountCheck = Objects.requireNonNull(bootCountCheck, "bootCountCheck");
    }

    /**
     * Runs the path for one {@code onStart}.
     *
     * @param stillStarted tells (on the main thread) whether the activity is still at least STARTED
     */
    void onStart(BooleanSupplier stillStarted) {
        io.execute(() -> {
            if (!onboardingCompleted()) {
                return;
            }
            main.execute(() -> {
                if (!stillStarted.getAsBoolean()) {
                    return;
                }
                MonitorControl c = control.get();
                c.startMonitoring();
                c.requestTick(TickReason.APP_OPEN);
                io.execute(bootCountCheck.get());
            });
        });
    }

    private boolean onboardingCompleted() {
        try {
            return flags.onboardingCompleted();
        } catch (StorageException e) {
            // logged by the store (R-13); as IF-27 resolve (SS-06 A-4 ruling) the DE flag decides
            try {
                return flags.monitoringConfigured();
            } catch (StorageException again) {
                return false;
            }
        }
    }
}
