package app.lightscout.domain.status;

/**
 * Top banner of S-01 in C-S01-06 priority order (CP-00A R-10).
 */
public enum Banner {
    /** No banner. */
    NONE,
    /** „Alarms” master switch off. */
    ALARMS_OFF,
    /** Low or High switched off. */
    SINGLE_ALARM_OFF,
    /** Required readiness items missing. */
    READINESS,
    /** Connection problem or data loss. */
    CONNECTION_OR_DATA_LOSS,
    /** Offline. */
    OFFLINE
}
