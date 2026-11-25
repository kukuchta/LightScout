package app.lightscout.domain.entry;

import java.util.List;
import java.util.Objects;

/**
 * One validation violation (CP-00A R-9).
 *
 * @param field  the field
 * @param code   the code
 * @param params unmodifiable message parameters (see {@link ValidationCode}), empty if none
 */
public record Violation(ValidationField field, ValidationCode code, List<Integer> params) {

    /**
     * Creates a violation; the parameter list is copied unmodifiably.
     *
     * @throws NullPointerException if a component or a parameter is null
     */
    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        params = List.copyOf(Objects.requireNonNull(params, "params"));
    }
}
