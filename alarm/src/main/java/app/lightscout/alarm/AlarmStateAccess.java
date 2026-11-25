package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.usecases.alarm.AlarmStateMemory;

/**
 * ENT-08 access of the alarm runtime (CP-00A R-35): reads and writes go through the {@code :usecases}
 * {@link AlarmStateMemory} shared with {@code SyncCycle} and {@code AlarmActions}, so that the single writer on
 * {@code monitorExecutor} keeps one authoritative state. Used on {@code monitorExecutor} only.
 */
class AlarmStateAccess {

    private final AlarmStateMemory memory;
    private final AlarmStateStore store;

    /**
     * Creates the access.
     *
     * @param memory in-memory ENT-08 shared with {@code :usecases}
     * @param store  IF-14 ({@code commit()} in ST-04)
     */
    AlarmStateAccess(AlarmStateMemory memory, AlarmStateStore store) {
        this.memory = memory;
        this.store = store;
    }

    /**
     * Returns the current state.
     *
     * @return the state ({@link AlarmState#empty()} fallback while the store fails and nothing is known)
     */
    AlarmState read() {
        return memory.read(store);
    }

    /**
     * Persists a state; never writes over the store while no real state is known (IR-36).
     *
     * @param next the new state
     * @return {@code true} when persisted
     */
    boolean write(AlarmState next) {
        if (!memory.known()) {
            return false;
        }
        return memory.write(store, next);
    }
}
