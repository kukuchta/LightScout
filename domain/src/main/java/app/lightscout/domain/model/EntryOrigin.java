package app.lightscout.domain.model;

/**
 * Nightscout source of an {@link Entry} (03 §6.1).
 */
public enum EntryOrigin {
    /** A document of the {@code treatments} collection. */
    TREATMENTS,
    /** A meter BG document ({@code type = mbg}) of the {@code entries} collection; always read-only (M-3). */
    ENTRIES_MBG
}
