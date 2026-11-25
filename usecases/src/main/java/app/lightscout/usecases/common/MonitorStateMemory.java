package app.lightscout.usecases.common;

import app.lightscout.domain.monitor.MonitorState;

/**
 * Last {@link MonitorState} of this process that derives from a successful IF-13 read (authoritative because
 * {@code monitorExecutor} is the single writer — R-21, R-34). Used by the sync cycle and the status projection when
 * IF-13 {@code get} fails, so neither falls back to {@code initial()} values. Monitor thread only.
 */
public final class MonitorStateMemory {

    private MonitorState state;

    /**
     * Returns the remembered state.
     *
     * @return the state, or {@code null} when none is known
     */
    public MonitorState get() {
        return state;
    }

    /**
     * Remembers a state that derives from a successful read.
     *
     * @param s the state
     */
    public void set(MonitorState s) {
        state = s;
    }

    /** Forgets the state (server-change reset R-71 / R-22, called by CP-02b's reset task on {@code monitorExecutor}). */
    public void reset() {
        state = null;
    }
}
