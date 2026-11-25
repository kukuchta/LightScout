package app.lightscout.ui.core;

/**
 * Result message passed back to the previous destination (CP-00A R-12, R-38). The receiving screen maps it to
 * its own Snackbar string ({@code s01_snackbar_*} / {@code s06_snackbar_saved}).
 */
public enum UiMessage {
    /** S-02 create succeeded. */
    ENTRY_SAVED,
    /** S-02 edit succeeded. */
    ENTRY_UPDATED,
    /** S-02 delete succeeded. */
    ENTRY_DELETED,
    /** S-06 edit saved (A-S06-3). */
    CONNECTION_SAVED
}
