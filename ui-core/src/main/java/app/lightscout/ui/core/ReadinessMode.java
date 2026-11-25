package app.lightscout.ui.core;

/**
 * Argument of S-07 Alarm readiness (CP-00A R-12, DESIGN §10 S-07 {@code mode: onboarding|check}).
 */
public enum ReadinessMode {
    /** Last onboarding step; „Done” finishes onboarding. */
    ONBOARDING,
    /** Opened from S-01 banner „Fix” or S-04 „Alarm readiness”. */
    CHECK
}
