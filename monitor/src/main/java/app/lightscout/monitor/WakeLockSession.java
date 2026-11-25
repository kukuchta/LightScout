package app.lightscout.monitor;

import java.util.function.LongSupplier;

/**
 * The tick owner's wake-lock session (🏛️ Q-65 / Q-68, IR-73). Pure Java over two seams so the release / timer
 * contract is JVM-tested; {@link TickWakeLock#forTick} supplies the Android handle and timer.
 *
 * <ul>
 *   <li>{@link #acquire()}: session start, one timed acquisition (≤ 30 s), periodic refresh timer every 20 s on a
 *       thread that is not {@code monitorExecutor}.</li>
 *   <li>{@link #refresh()}: timed re-acquisition (≤ 30 s) while held, within the 120 s cap ({@link WakeLockBudget}).
 *       </li>
 *   <li>{@link #release()}: cancels the timer **and** releases the lock in the same call — {@link TickRunner} calls
 *       it from {@code finally}, also when IF-20 throws.</li>
 * </ul>
 */
final class WakeLockSession implements RuntimePorts.WakeLock {

    /** A non-reference-counted partial wake lock. */
    interface Handle {

        /**
         * Timed acquisition.
         *
         * @param timeoutMs timeout
         */
        void acquire(long timeoutMs);

        /** Releases the lock. */
        void release();

        /**
         * Tells whether the lock is held.
         *
         * @return {@code true} when held
         */
        boolean isHeld();
    }

    /** Periodic timer. */
    interface Timer {

        /**
         * Schedules a periodic task.
         *
         * @param task     task
         * @param periodMs period (first run after one period)
         * @return a canceller
         */
        Runnable schedulePeriodic(Runnable task, long periodMs);
    }

    private final Handle handle;
    private final Timer timer;
    private final LongSupplier elapsed;
    private final WakeLockBudget budget = new WakeLockBudget();
    private Runnable cancelTimer;

    /**
     * Creates the session.
     *
     * @param handle  wake lock handle
     * @param timer   refresh timer
     * @param elapsed elapsed realtime source
     */
    WakeLockSession(Handle handle, Timer timer, LongSupplier elapsed) {
        this.handle = handle;
        this.timer = timer;
        this.elapsed = elapsed;
    }

    @Override
    public synchronized void acquire() {
        budget.start(elapsed.getAsLong());
        acquireTimed();
        cancelTimer();
        cancelTimer = timer.schedulePeriodic(this::refresh, WakeLockBudget.REFRESH_PERIOD_MS);
    }

    @Override
    public synchronized void refresh() {
        if (!budget.active() || !handle.isHeld()) {
            return;
        }
        acquireTimed();
    }

    @Override
    public synchronized void release() {
        cancelTimer();
        budget.end();
        if (handle.isHeld()) {
            handle.release();
        }
    }

    private void acquireTimed() {
        long timeout = budget.nextTimeoutMs(elapsed.getAsLong());
        if (timeout > 0L) {
            handle.acquire(timeout);
        }
    }

    private void cancelTimer() {
        if (cancelTimer != null) {
            cancelTimer.run();
            cancelTimer = null;
        }
    }
}
