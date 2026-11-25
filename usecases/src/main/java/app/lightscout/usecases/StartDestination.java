package app.lightscout.usecases;

/** Start destination of a cold start without a restored back stack (IF-27, design §9.1, CP-00A R-10, R-72). */
public enum StartDestination {
    /** S-05: the disclaimer is not accepted yet (DS:D-058). */
    DISCLAIMER,
    /** S-06: disclaimer accepted, no stored URL + token. */
    CONNECTION,
    /** S-07: connection stored, onboarding not completed. */
    READINESS,
    /** S-01: onboarding completed (also when the connection fails later — no automatic redirect). */
    HOME
}
