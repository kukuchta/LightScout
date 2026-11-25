package app.lightscout.monitor;

/**
 * Seam over {@code AlarmManager} for the four {@code :monitor} alarms (JVM-testable; Android adapter
 * {@link AndroidAlarmPort}). Every method targets an immutable explicit PendingIntent (SR-22).
 */
interface AlarmPort {

    /** The alarms owned by {@code :monitor} (CP-00A R-36). */
    enum Kind {
        /** Sync tick → {@code MonitorAlarmReceiver} {@code …action.TICK}. */
        TICK,
        /** Ringing heartbeat → {@code MonitorAlarmReceiver} {@code …action.HEARTBEAT}. */
        HEARTBEAT,
        /** WF-04 5 s retry → {@code MonitorAlarmReceiver} {@code …action.BOOT_RETRY}. */
        BOOT_RETRY,
        /** Watchdog → {@code WatchdogReceiver} {@code …action.WATCHDOG}. */
        WATCHDOG
    }

    /**
     * Tells whether exact alarms may be scheduled ({@code canScheduleExactAlarms()} on API 31+, else true).
     *
     * @return {@code true} when allowed
     */
    boolean canScheduleExact();

    /**
     * {@code setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)} (ADR-043, F14).
     *
     * @param kind      alarm
     * @param elapsedMs trigger in elapsed realtime
     * @throws SecurityException when the platform refuses the exact alarm
     */
    void setExactElapsed(Kind kind, long elapsedMs);

    /**
     * {@code setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)}: inexact fallback (WF-01 step 6 **P**).
     *
     * @param kind      alarm
     * @param elapsedMs trigger in elapsed realtime
     */
    void setInexactElapsed(Kind kind, long elapsedMs);

    /**
     * {@code setAlarmClock(…)}: VR-07 fallback for the expected-reading tick only (D-020; wall clock by API).
     *
     * @param kind   alarm (always {@link Kind#TICK})
     * @param wallMs trigger in epoch ms
     * @throws SecurityException when the platform refuses the exact alarm
     */
    void setAlarmClock(Kind kind, long wallMs);

    /**
     * Cancels the alarm.
     *
     * @param kind alarm
     */
    void cancel(Kind kind);
}
