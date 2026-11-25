package app.lightscout.feature.home;

/**
 * One-time navigation request from {@link HomeModel}; {@link HomeNavigation} turns it into an IF-29
 * {@code Navigator} call (CP-00 R-4: cross-feature navigation only through IF-29).
 */
public enum HomeCommand {
    /** A-S01-3 „Statistics” (C-S01-03) → S-03. */
    OPEN_STATISTICS,
    /** A-S01-4 „Settings” (C-S01-04) → S-04. */
    OPEN_SETTINGS,
    ADD_CARBS,
    ADD_BOLUS,
    ADD_BG,
    ADD_SITE,
    ADD_SENSOR,
    /** CP-11 marker tooltip edit → S-02 edit. */
    EDIT_ENTRY,
    /** A-S01-9 banner „Fix” → S-07 in check mode. */
    OPEN_READINESS_CHECK,
    /** A-S01-9 banner „Fix connection” → S-06 in edit mode. */
    OPEN_CONNECTION_EDIT
}
