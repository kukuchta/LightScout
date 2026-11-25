package app.lightscout.domain.port;

import app.lightscout.domain.alarm.AlarmState;

/**
 * Alarm state persistence (IF-14, CP-00A R-22). Written only on {@code monitorExecutor}.
 * Failures throw {@link StorageException}.
 */
public interface AlarmStateStore {

    /**
     * Returns the stored state ({@link AlarmState#empty()} when none).
     *
     * @return state
     */
    AlarmState get();

    /**
     * Stores the state.
     *
     * @param s state
     */
    void put(AlarmState s);

    /** Deletes the state. */
    void clear();
}
