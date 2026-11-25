package app.lightscout.domain.entry;

import java.util.List;
import java.util.Objects;

/**
 * Result of a validation (CP-00A R-9): codes and parameters, never strings.
 *
 * @param violations unmodifiable violations, empty when valid
 */
public record ValidationResult(List<Violation> violations) {

    private static final ValidationResult OK = new ValidationResult(List.of());

    /**
     * Creates a result; the list is copied unmodifiably.
     *
     * @throws NullPointerException if the list or an element is null
     */
    public ValidationResult {
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    /**
     * Tells whether there are no violations.
     *
     * @return {@code true} when valid
     */
    public boolean valid() {
        return violations.isEmpty();
    }

    /**
     * Returns the valid result.
     *
     * @return a result without violations
     */
    public static ValidationResult ok() {
        return OK;
    }
}
