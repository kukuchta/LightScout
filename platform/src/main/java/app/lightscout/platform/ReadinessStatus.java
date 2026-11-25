package app.lightscout.platform;

/**
 * Status of one readiness item (CP-00A R-11). „Permanently denied” is computed by the UI only (CP-00A R-74).
 */
public enum ReadinessStatus {
    /** Done (✓). */
    DONE,
    /** Needs attention (!). */
    NEEDS_ATTENTION,
    /** Recommended (↗) — item 6 only. */
    RECOMMENDED
}
