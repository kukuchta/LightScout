package app.lightscout.usecases;

import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.StatusSnapshot;

/**
 * CMP-11 StatusRepository — IF-21: the single source of the current status for S-01, S-09, S-10, S-12
 * (ADR-025; CP-02 §8.3, CP-00A R-25).
 */
public interface StatusRepository {

    /**
     * Returns the latest snapshot from memory; callable on any thread including main.
     *
     * @return the current snapshot (never null)
     */
    StatusSnapshot current();

    /**
     * Registers a listener; listeners fire on {@code monitorExecutor} on every snapshot change.
     *
     * @param l the listener
     */
    void addListener(StatusListener l);

    /**
     * Removes a listener.
     *
     * @param l the listener
     */
    void removeListener(StatusListener l);

    /** Re-projects on {@code monitorExecutor} without network (time-derived fields, readiness, connectivity). */
    void refresh();

    /** Requests an immediate tick ({@code MonitorControl.requestTick(USER_RETRY)}) — S-01 „Retry”. */
    void retry();
}
