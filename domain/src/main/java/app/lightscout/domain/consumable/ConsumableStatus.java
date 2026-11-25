package app.lightscout.domain.consumable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Age and state of a consumable (F-12, CP-00A R-8). All nullable components are null for
 * {@link ConsumableState#NONE_FOUND}.
 *
 * @param kind       the consumable
 * @param state      the counter state
 * @param lastChange time of the latest change entry; nullable
 * @param age        absolute duration since the change (T-3); nullable
 * @param due24hAt   {@code lastChange + lifetime − 24 h}; nullable
 * @param dueAt      {@code lastChange + lifetime}; nullable
 */
public record ConsumableStatus(ConsumableKind kind, ConsumableState state, Instant lastChange,
                               Duration age, Instant due24hAt, Instant dueAt) {

    /**
     * Creates a status.
     *
     * @throws NullPointerException if {@code kind} or {@code state} is null
     */
    public ConsumableStatus {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
    }
}
