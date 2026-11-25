package app.lightscout.monitor;

import android.content.Context;

import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Logger;

import java.util.Objects;

/**
 * F5 boot-count check (06 §11 F5, VR-28; Q-59 as corrected by IR-76 and 🏛️ Q-67). The handled
 * {@code Settings.Global.BOOT_COUNT} is stored in ST-05 by {@code BootReceiver} on {@code BOOT_COMPLETED} (before the
 * service start), by the {@code ONBOARDING_DONE} tick and, healing an unhandled boot, by the first completed tick of
 * a process ({@code TickRunner}). {@code LOCKED_BOOT_COMPLETED} writes nothing.
 *
 * <p>A mismatch at app start or on a JobScheduler run logs {@code BOOT_NOT_HANDLED} at most once per boot count per
 * process ({@link BootNotHandledGuard}). The check is skipped **only** in a process that itself handled a boot
 * broadcast ({@link BootNotHandledGuard#markBootHandled()}); every other process (app open, job run,
 * watchdog-started) runs it. It never writes and never starts anything („log only” = no user-visible signal).
 *
 * <p>Origins: APP_OPEN ({@code :app} {@code MainActivity.onStart} after {@code startMonitoring()}, on
 * {@code ioExecutor}) and the JobService ({@code MonitorJobService}). **Never** from {@code Application.onCreate}:
 * in a process started by {@code BOOT_COMPLETED} it would run before {@code BootReceiver} marks the process
 * (Moderator decision after Q-67). Reads DE storage and {@code Settings.Global}: never on the main thread.
 */
public final class BootCountCheck {

    private final AppFlagsStore flags;
    private final RuntimePorts.BootCountSource bootCount;
    private final Logger logger;
    private final BootNotHandledGuard guard;

    /**
     * Creates the check for the app.
     *
     * @param context any context ({@code Settings.Global} reader)
     * @param flags   IF-60 (DE {@code monitoringConfigured}, {@code handledBootCount})
     * @param logger  IF-66
     */
    public BootCountCheck(Context context, AppFlagsStore flags, Logger logger) {
        this(new SettingsBootCountSource(context), flags, logger, BootNotHandledGuard.PROCESS);
    }

    /**
     * Creates the check with explicit collaborators.
     *
     * @param bootCount boot-count source
     * @param flags     IF-60
     * @param logger    IF-66
     * @param guard     process-scoped guard
     */
    BootCountCheck(RuntimePorts.BootCountSource bootCount, AppFlagsStore flags, Logger logger,
                   BootNotHandledGuard guard) {
        this.bootCount = Objects.requireNonNull(bootCount, "bootCount");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Runs the check.
     *
     * @return {@code true} when {@code BOOT_NOT_HANDLED} was logged now
     */
    public boolean run() {
        try {
            if (!flags.monitoringConfigured() || guard.bootHandled()) {
                return false;
            }
            long current = bootCount.bootCount();
            if (current < 0 || current == flags.handledBootCount()) {
                return false;
            }
            return guard.logOnce(current, logger);
        } catch (RuntimeException e) {
            return false;   // a diagnostic check never throws
        }
    }
}
