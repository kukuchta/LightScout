package app.lightscout.monitor;

/**
 * Why {@link MonitorService} is being started — the F4 start-origin table (06 §11 F4). The constant name is the
 * {@code FGS_START} / {@code FGS_START_DENIED} log detail (enum names only — SR-07).
 */
enum StartOrigin {
    /** {@code MonitorControl.startMonitoring()} from a foreground activity (S-07 „Done”, app open). */
    ACTIVITY,
    /** Tick exact alarm. */
    TICK,
    /** Watchdog exact alarm. */
    WATCHDOG,
    /** Ringing heartbeat exact alarm. */
    HEARTBEAT,
    /** WF-04 5 s retry alarm after a refused boot start. */
    BOOT_RETRY,
    /** JobScheduler safety net. */
    JOB_NET,
    /** {@code START_STICKY} restart by the system (null intent). */
    STICKY_RESTART,
    /** {@code BOOT_COMPLETED}. */
    BOOT,
    /** {@code MY_PACKAGE_REPLACED}. */
    PACKAGE_REPLACED,
    /** {@code TIME_SET} / {@code TIMEZONE_CHANGED} (WF-10). */
    TIME_CHANGED
}
