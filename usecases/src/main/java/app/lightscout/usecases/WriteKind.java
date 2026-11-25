package app.lightscout.usecases;

/**
 * Kind of a successful entry write (CP-00A R-10). A deduplicated POST (200 + {@code isDeduplication}) is
 * {@link #CREATED} (GIR-3, 05 IF-38).
 */
public enum WriteKind {
    /** IF-38 create succeeded (201, or 200 + {@code isDeduplication}). */
    CREATED,
    /** IF-40 update succeeded. */
    UPDATED,
    /** IF-41 delete succeeded. */
    DELETED
}
