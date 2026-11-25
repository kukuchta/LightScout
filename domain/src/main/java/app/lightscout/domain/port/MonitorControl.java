package app.lightscout.domain.port;

import app.lightscout.domain.status.TickReason;

/**
 * Monitor control port (IF-62, CP-00A R-32), implemented by {@code :monitor}.
 */
public interface MonitorControl {

    /**
     * Enqueues a tick on {@code monitorExecutor}.
     *
     * @param r reason
     */
    void requestTick(TickReason r);

    /** Enqueues an alarm re-evaluation without network. */
    void reevaluateAlarms();

    /**
     * Starts the monitor service if not running (idempotent). The caller must be in a foreground-allowed context
     * and then requests a tick.
     */
    void startMonitoring();
}
