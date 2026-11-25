package app.lightscout.alarm;

/**
 * Implemented by the {@code Application} class in {@code :app} (CP-15 composition root, R-6). {@link AlarmActivity},
 * {@link SnoozeReceiver} and {@link AlarmDeleteReceiver} obtain the process-wide {@link AlarmRuntime} only through
 * this interface — never by constructing collaborators themselves (R-2, R-3).
 */
public interface AlarmRuntimeProvider {

    /**
     * Returns the process-wide alarm runtime (the same instance that the container hands to {@code SyncCycle} /
     * {@code AlarmActions} as IF-16). Called on the main thread by the {@code :alarm} components, which are not
     * direct-boot aware (only after the first unlock); it must not do disk or network work on the calling thread.
     *
     * @return the runtime
     */
    AlarmRuntime alarmRuntime();
}
