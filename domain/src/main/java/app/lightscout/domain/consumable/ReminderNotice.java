package app.lightscout.domain.consumable;

import java.time.Duration;
import java.util.Objects;

/**
 * A reminder to post (IF-17, CP-00A R-8).
 *
 * @param kind           the consumable
 * @param stage          the stage
 * @param basedOnEntryId id of the change entry the reminder is based on
 * @param age            consumable age at posting
 * @param lifetimeDays   lifetime setting in days
 */
public record ReminderNotice(ConsumableKind kind, ReminderStage stage, String basedOnEntryId,
                             Duration age, int lifetimeDays) {

    /**
     * Creates a notice.
     *
     * @throws NullPointerException if a reference component is null
     */
    public ReminderNotice {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(basedOnEntryId, "basedOnEntryId");
        Objects.requireNonNull(age, "age");
    }
}
