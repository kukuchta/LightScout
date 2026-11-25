package app.lightscout.domain.entry;

/**
 * Field a {@link Violation} refers to (CP-00A R-9).
 */
public enum ValidationField {
    /** S-02 entry type. */
    TYPE,
    /** S-02 value. */
    VALUE,
    /** S-02 time. */
    TIME,
    /** An S-04 setting. */
    SETTING
}
