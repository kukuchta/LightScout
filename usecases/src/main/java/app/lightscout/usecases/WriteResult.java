package app.lightscout.usecases;

import java.util.Objects;

/**
 * Successful result of IF-23 {@code save} / {@code delete} (CP-00A R-10). Failures are delivered as a failed
 * {@code Result} whose outcome and detail code are mapped to S-02 copy by CP-13 (R-50, R-51).
 *
 * @param kind       what was written
 * @param identifier Nightscout identifier of the entry
 */
public record WriteResult(WriteKind kind, String identifier) {

    /**
     * Creates the result.
     *
     * @throws NullPointerException if a component is null
     */
    public WriteResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identifier, "identifier");
    }
}
