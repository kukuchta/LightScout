package app.lightscout.alarm;

/**
 * Delayed-task seam for the player thread (a {@code Handler} in the app, a manual fake in JVM tests).
 */
interface Scheduler {

    /**
     * Schedules a task.
     *
     * @param task    the task
     * @param delayMs delay in ms
     * @return a handle that cancels the task
     */
    Cancellable schedule(Runnable task, long delayMs);

    /**
     * Handle of a scheduled task.
     */
    interface Cancellable {

        /** Cancels the task if it has not run yet. */
        void cancel();
    }
}
