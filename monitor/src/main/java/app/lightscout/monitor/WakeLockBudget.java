package app.lightscout.monitor;

/**
 * Timing rule of the tick wake lock (CP-00 §4, WF-01 step 1, PERM-12; 🏛️ Q-65 / IR-73). Pure.
 *
 * <ul>
 *   <li>Every acquisition is timed and lasts at most {@link #ACQUIRE_MAX_MS} (30 s) — the PERM-12 reading
 *       „short wake locks: sync ≤ 30 s” applies per acquisition.</li>
 *   <li>A session (one tick / evaluation) is refreshed while it runs, but never beyond {@link #SESSION_CAP_MS}
 *       (120 s) after it started; the last acquisition is shortened to end exactly at the cap.</li>
 * </ul>
 */
final class WakeLockBudget {

    /** Maximum single acquisition. */
    static final long ACQUIRE_MAX_MS = 30_000L;
    /** Hard cap per session. */
    static final long SESSION_CAP_MS = 120_000L;
    /** Period of the adapter's automatic refresh while held (IF-20 has no per-step hook). */
    static final long REFRESH_PERIOD_MS = 20_000L;

    private long startedAt = -1L;

    /**
     * Starts a session.
     *
     * @param nowElapsedMs elapsed realtime
     */
    void start(long nowElapsedMs) {
        startedAt = nowElapsedMs;
    }

    /** Ends the session. */
    void end() {
        startedAt = -1L;
    }

    /**
     * Tells whether a session is active.
     *
     * @return {@code true} between {@link #start} and {@link #end}
     */
    boolean active() {
        return startedAt >= 0L;
    }

    /**
     * Returns the timeout of the next acquisition.
     *
     * @param nowElapsedMs elapsed realtime
     * @return ms to acquire for (≤ 30 s), or 0 when no session is active or the 120 s cap is spent
     */
    long nextTimeoutMs(long nowElapsedMs) {
        if (startedAt < 0L) {
            return 0L;
        }
        long left = SESSION_CAP_MS - (nowElapsedMs - startedAt);
        return left <= 0L ? 0L : Math.min(ACQUIRE_MAX_MS, left);
    }
}
