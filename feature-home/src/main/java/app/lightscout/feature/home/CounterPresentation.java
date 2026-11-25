package app.lightscout.feature.home;

import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Display-only mapping for the site and sensor age cards. */
public record CounterPresentation(Duration age, State state) {

    public CounterPresentation {
        Objects.requireNonNull(state, "state");
    }

    static CounterPresentation from(ConsumableStatus status, Instant now) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        if (status.state() == ConsumableState.LOADING) {
            return new CounterPresentation(null, State.LOADING);
        }
        if (status.lastChange() == null) {
            return new CounterPresentation(null, State.NONE_FOUND);
        }
        Duration age = Duration.between(status.lastChange(), now).abs();
        State state = status.dueAt() != null && !now.isBefore(status.dueAt()) ? State.EXPIRED
                : status.due24hAt() != null && !now.isBefore(status.due24hAt()) ? State.CHANGE_SOON
                : State.NORMAL;
        return new CounterPresentation(age, state);
    }

    public enum State {
        /** DCR-45: the first backfill may still bring a change entry („Loading…”). */
        LOADING,
        NONE_FOUND,
        NORMAL,
        CHANGE_SOON,
        EXPIRED
    }
}
