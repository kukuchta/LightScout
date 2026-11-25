package app.lightscout.monitor;

import java.time.Instant;
import java.util.Objects;

/**
 * The next sync tick.
 *
 * @param at    wall-clock instant of the tick (converted to elapsed realtime when armed — F14)
 * @param phase the rule that produced it
 */
record TickPlan(Instant at, TickPhase phase) {

    /**
     * Creates the plan.
     *
     * @throws NullPointerException if a component is null
     */
    TickPlan {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(phase, "phase");
    }
}
