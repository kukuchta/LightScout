package app.lightscout.domain.consumable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Site / sensor age, counter state and reminder instants (CMP-06, IF-05; T-3, DS:D-026, DS:D-052;
 * CP-00A R-8, R-62).
 *
 * <p>Age is the absolute duration between instants (DST-independent). A lifetime of {@code n} days is
 * {@code n × 24 h}. CHANGE_SOON from {@code due24hAt} until {@code dueAt}; EXPIRED from {@code dueAt}.
 */
public final class ConsumableCalculator {

    private static final Duration ONE_DAY = Duration.ofHours(24);

    /**
     * Creates the stateless rule component.
     */
    public ConsumableCalculator() {
        // no state
    }

    /**
     * Computes the status of a consumable.
     *
     * @param kind         the consumable
     * @param lastChange   time of the latest change entry; {@code null} → {@link ConsumableState#NONE_FOUND}
     * @param lifetimeDays lifetime setting in days
     * @param now          evaluation instant
     * @return the status
     */
    public ConsumableStatus status(ConsumableKind kind, Instant lastChange, int lifetimeDays, Instant now) {
        if (lastChange == null) {
            return new ConsumableStatus(kind, ConsumableState.NONE_FOUND, null, null, null, null);
        }
        List<Instant> times = reminderTimes(lastChange, lifetimeDays);
        Instant due24hAt = times.get(0);
        Instant dueAt = times.get(1);
        ConsumableState state;
        if (!now.isBefore(dueAt)) {
            state = ConsumableState.EXPIRED;
        } else if (!now.isBefore(due24hAt)) {
            state = ConsumableState.CHANGE_SOON;
        } else {
            state = ConsumableState.NORMAL;
        }
        return new ConsumableStatus(kind, state, lastChange, Duration.between(lastChange, now), due24hAt, dueAt);
    }

    /**
     * Returns the reminder instants (T-3).
     *
     * @param lastChange   time of the change
     * @param lifetimeDays lifetime in days
     * @return unmodifiable {@code [due24hAt, dueAt]} = {@code [lastChange + lifetime − 24 h, lastChange + lifetime]}
     */
    public List<Instant> reminderTimes(Instant lastChange, int lifetimeDays) {
        Instant dueAt = lastChange.plus(ONE_DAY.multipliedBy(lifetimeDays));
        return List.of(dueAt.minus(ONE_DAY), dueAt);
    }
}
