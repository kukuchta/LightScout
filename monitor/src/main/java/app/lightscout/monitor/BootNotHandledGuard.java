package app.lightscout.monitor;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * Process-scoped state of the F5 boot-count check (🏛️ Q-67, IR-76). One instance per process
 * ({@link #PROCESS}); in-memory only, so a new process starts clean.
 *
 * <ul>
 *   <li>{@link #markBootHandled()} — set by {@code BootReceiver} when it handles {@code BOOT_COMPLETED} or
 *       {@code LOCKED_BOOT_COMPLETED}, before the service start. The app-start / job check skips only in a process
 *       where this flag is set.</li>
 *   <li>{@link #logOnce(long, Logger)} — {@code BOOT_NOT_HANDLED} at most once per boot count in this process
 *       (shared by the check and the first-tick heal).</li>
 * </ul>
 */
final class BootNotHandledGuard {

    /** The instance of this process. */
    static final BootNotHandledGuard PROCESS = new BootNotHandledGuard();

    private volatile boolean bootHandled;
    private long loggedForBootCount = -1L;

    /** Marks that this process handled a boot broadcast. */
    void markBootHandled() {
        bootHandled = true;
    }

    /**
     * Tells whether this process handled a boot broadcast.
     *
     * @return {@code true} after {@link #markBootHandled()}
     */
    boolean bootHandled() {
        return bootHandled;
    }

    /**
     * Logs {@code BOOT_NOT_HANDLED} unless already logged for this boot count in this process.
     *
     * @param count  current boot count
     * @param logger IF-66
     * @return {@code true} when logged now
     */
    synchronized boolean logOnce(long count, Logger logger) {
        if (count == loggedForBootCount) {
            return false;
        }
        loggedForBootCount = count;
        logger.event(EventCode.BOOT_NOT_HANDLED);
        return true;
    }
}
