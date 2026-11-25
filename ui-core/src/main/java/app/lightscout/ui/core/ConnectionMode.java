package app.lightscout.ui.core;

/**
 * Argument of S-06 Nightscout connection (CP-00A R-12, DESIGN §10 S-06 {@code mode: onboarding|edit}).
 */
public enum ConnectionMode {
    /** Opened inside onboarding (S-05 → S-06 → S-07). */
    ONBOARDING,
    /** Opened from S-01 „Fix connection” or S-04 „Nightscout connection”. */
    EDIT
}
