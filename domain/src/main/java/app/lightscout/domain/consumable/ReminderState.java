package app.lightscout.domain.consumable;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted reminder state of one consumable (ENT-09, IF-63, CP-00A R-8).
 *
 * @param kind           the consumable
 * @param basedOnEntryId id of the change entry
 * @param due24hAt       24 h reminder instant
 * @param dueAt          expiry instant
 * @param notified24h    the 24 h reminder was posted
 * @param notifiedAt     the expiry reminder was posted
 */
public record ReminderState(ConsumableKind kind, String basedOnEntryId, Instant due24hAt, Instant dueAt,
                            boolean notified24h, boolean notifiedAt) {

    /**
     * Creates a reminder state.
     *
     * @throws NullPointerException if a reference component is null
     */
    public ReminderState {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(basedOnEntryId, "basedOnEntryId");
        Objects.requireNonNull(due24hAt, "due24hAt");
        Objects.requireNonNull(dueAt, "dueAt");
    }
}
