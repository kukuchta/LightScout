package app.lightscout.usecases.alarm;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.StorageException;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * In-memory copy of ENT-08 shared by the sync cycle, the status projection and {@link DefaultAlarmActions}
 * (WF-02 step 5; CP-00A R-34, R-35). Used on {@code monitorExecutor} only: the single writer makes the last written
 * state authoritative, so when an IF-14 {@code put} fails the state is kept here (dirty) and used by every
 * following evaluation until a {@code put} succeeds — a snooze is never lost and the snoozed type does not ring again.
 *
 * <p>A snooze while no state is known (IF-14 {@code get} failing since process start) is kept as a pending snooze:
 * it is never written over the store (that would wipe other types' snoozes and {@code :alarm}'s saved volume) and is
 * merged into the store state on the next successful read (IR-36).
 */
public final class AlarmStateMemory {

    private AlarmState state;
    private boolean dirty;
    private boolean failed;
    private final Map<AlarmType, Instant> pendingSnoozes = new EnumMap<>(AlarmType.class);

    /**
     * Returns the current state: the unsaved in-memory state when dirty, else the store's (remembered, with pending
     * snoozes merged); on a store failure the last known state, or {@link AlarmState#empty()} with the pending
     * snoozes applied (not {@link #known()}).
     *
     * @param store IF-14
     * @return the state
     */
    public AlarmState read(AlarmStateStore store) {
        if (dirty && state != null) {
            return state;
        }
        try {
            AlarmState s = store.get();
            if (!pendingSnoozes.isEmpty()) {
                for (Map.Entry<AlarmType, Instant> e : pendingSnoozes.entrySet()) {
                    s = AlarmStateTransitions.afterSnooze(s, e.getKey(), e.getValue());
                }
                pendingSnoozes.clear();
                dirty = true;
            }
            state = s;
        } catch (StorageException e) {
            failed = true;
            if (state == null) {
                AlarmState s = AlarmState.empty();
                for (Map.Entry<AlarmType, Instant> p : pendingSnoozes.entrySet()) {
                    s = AlarmStateTransitions.afterSnooze(s, p.getKey(), p.getValue());
                }
                return s;
            }
        }
        return state;
    }

    /**
     * Remembers a new state and persists it; a failed {@code put} leaves it dirty for the next write.
     *
     * @param store IF-14
     * @param next  the new state
     * @return {@code true} when persisted
     */
    public boolean write(AlarmStateStore store, AlarmState next) {
        state = next;
        try {
            store.put(next);
            dirty = false;
            return true;
        } catch (StorageException e) {
            failed = true;
            dirty = true;
            return false;
        }
    }

    /**
     * Applies a snooze (R-29): written through when a state is known, else kept pending (never written over the
     * store) and merged on the next successful read.
     *
     * @param store IF-14
     * @param type  snoozed type
     * @param until end of the snooze
     */
    public void snooze(AlarmStateStore store, AlarmType type, Instant until) {
        AlarmState current = read(store);
        if (known()) {
            write(store, AlarmStateTransitions.afterSnooze(current, type, until));
        } else {
            pendingSnoozes.put(type, until);
        }
    }

    /**
     * Tells whether a real state (read from the store or written in this process) is known; {@code false} means the
     * last {@link #read} returned the {@code empty()} fallback, which must never be written over the store.
     *
     * @return {@code true} when known
     */
    public boolean known() {
        return state != null;
    }

    /**
     * Tells whether the in-memory state still has to be persisted.
     *
     * @return {@code true} after a failed {@code put}
     */
    public boolean dirty() {
        return dirty;
    }

    /**
     * Returns and clears the „an IF-14 call failed” flag (R-20: the tick reports STORAGE_ERROR).
     *
     * @return {@code true} when a get or put failed since the last call
     */
    public boolean takeFailure() {
        boolean f = failed;
        failed = false;
        return f;
    }

    /**
     * Forgets everything (server-change reset R-71 / R-22, called by CP-02b's reset task on {@code monitorExecutor}
     * after it stored {@code keepRingingOnly()}); the next read comes from the store again.
     */
    public void reset() {
        state = null;
        dirty = false;
        failed = false;
        pendingSnoozes.clear();
    }
}
