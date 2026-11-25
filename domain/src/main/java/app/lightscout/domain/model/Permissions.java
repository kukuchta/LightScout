package app.lightscout.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * Permission letters per collection reported by Nightscout {@code apiPermissions} (IF-32, CP-00A R-2),
 * e.g. {@code TREATMENTS → "crud"}.
 *
 * @param letters unmodifiable map from collection to its permission letters ({@code c r u d})
 */
public record Permissions(Map<NsCollection, String> letters) {

    /**
     * Creates the permissions; the map is copied unmodifiably.
     *
     * @throws NullPointerException if the map, a key or a value is null
     */
    public Permissions {
        letters = Map.copyOf(Objects.requireNonNull(letters, "letters"));
    }

    /**
     * Tells whether an operation is permitted on a collection.
     *
     * @param c  the collection
     * @param op the operation letter, one of {@code c r u d}
     * @return {@code true} if the letters of {@code c} contain {@code op}; {@code false} if the collection is absent
     */
    public boolean has(NsCollection c, char op) {
        String granted = c == null ? null : letters.get(c);
        return granted != null && granted.indexOf(op) >= 0;
    }
}
