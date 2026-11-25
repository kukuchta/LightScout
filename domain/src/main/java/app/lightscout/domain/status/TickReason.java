package app.lightscout.domain.status;

/**
 * Why a sync tick runs (CP-00A R-10). Daily maintenance is not a reason: it runs inside the first tick after 03:00.
 */
public enum TickReason {
    /** Regular schedule. */
    SCHEDULED,
    /** Watchdog alarm. */
    WATCHDOG,
    /** JobScheduler safety net. */
    JOB_NET,
    /** Monitor service restarted. */
    SERVICE_RESTART,
    /** Device boot. */
    BOOT,
    /** App updated. */
    PACKAGE_REPLACED,
    /** Clock or time zone changed. */
    TIME_CHANGED,
    /** Network regained. */
    CONNECTIVITY_REGAINED,
    /** Screen on with stale data. */
    SCREEN_ON_STALE,
    /** App opened. */
    APP_OPEN,
    /** Onboarding finished. */
    ONBOARDING_DONE,
    /** Connection reconfigured. */
    RECONFIGURED,
    /** User pressed Retry. */
    USER_RETRY,
    /** A site / sensor change entry was written. */
    ENTRY_WRITTEN,
    /** Ringing keep-alive; used only by re-evaluate paths (R-35). */
    HEARTBEAT
}
