package app.lightscout.domain.port;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderState;

import java.util.Optional;

/**
 * Reminder state persistence (IF-63, ENT-09, CP-00A R-22). Written only on {@code monitorExecutor}.
 * Failures throw {@link StorageException}.
 */
public interface ReminderStateStore {

    /**
     * Returns the state of a consumable.
     *
     * @param k consumable
     * @return the state, if any
     */
    Optional<ReminderState> get(ConsumableKind k);

    /**
     * Stores a state.
     *
     * @param s state
     */
    void put(ReminderState s);

    /** Deletes all states. */
    void clear();
}
