package app.lightscout.domain.entry;

/**
 * Source of an entry for labels (04 §4.3 M-6, CP-00A R-9).
 */
public enum SourceKind {
    /** Created by LightScout (own). */
    LIGHTSCOUT,
    /** {@code enteredBy} empty or {@code "undefined"}. */
    NIGHTSCOUT,
    /** Any other {@code enteredBy}: the uploader name. */
    UPLOADER
}
