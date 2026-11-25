package app.lightscout.domain.port;

import app.lightscout.domain.monitor.MonitorState;

/**
 * Monitor state persistence (IF-13, CP-00A R-22). Written only on {@code monitorExecutor}.
 * Failures throw {@link StorageException}.
 */
public interface MonitorStateStore {

    /**
     * Returns the stored state ({@link MonitorState#initial()} when none).
     *
     * @return state
     */
    MonitorState get();

    /**
     * Stores the state.
     *
     * @param s state
     */
    void put(MonitorState s);

    /** Deletes the state. */
    void clear();
}
