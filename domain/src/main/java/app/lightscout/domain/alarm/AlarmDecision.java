package app.lightscout.domain.alarm;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Result of one alarm evaluation (IF-01, 03 §6.2).
 *
 * @param commands       unmodifiable commands for IF-16, possibly empty
 * @param nextEvaluation earliest future instant at which the decision may change (F12); nullable
 */
public record AlarmDecision(List<AlarmCommand> commands, Instant nextEvaluation) {

    /**
     * Creates a decision; the command list is copied unmodifiably.
     *
     * @throws NullPointerException if {@code commands} or one of its elements is null
     */
    public AlarmDecision {
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
    }
}
