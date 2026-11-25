package app.lightscout.domain.alarm;

/**
 * Audible alarm types (03 CMP-01, CP-00A R-4). Declaration order is the AL-6 priority, highest first.
 */
public enum AlarmType {
    /** Urgent low — always on (DS:D-021). */
    URGENT_LOW,
    /** Low. */
    LOW,
    /** No data — no new reading for the data-loss threshold. */
    NO_DATA,
    /** LightScout is not running — watchdog (DS:D-033). */
    NOT_RUNNING,
    /** High. */
    HIGH
}
