package app.lightscout.domain.entry;

import java.util.Objects;

/**
 * Source label of an entry (M-6, CP-00A R-9).
 *
 * @param kind         the source kind
 * @param uploaderName {@link SourceKind#UPLOADER} only: the (M-11-capped) {@code enteredBy}; nullable
 */
public record SourceLabel(SourceKind kind, String uploaderName) {

    /**
     * Creates a label.
     *
     * @throws NullPointerException if {@code kind} is null
     */
    public SourceLabel {
        Objects.requireNonNull(kind, "kind");
    }
}
