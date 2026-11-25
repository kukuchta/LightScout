package app.lightscout.domain.entry;

/**
 * Mode of an S-02 entry draft (CP-00A R-9).
 */
public enum EntryMode {
    /** New entry. */
    CREATE,
    /** Editing an existing own entry. */
    EDIT
}
