package app.lightscout.usecases.sync;

import app.lightscout.domain.consumable.ConsumableCalculator;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.consumable.ReminderStage;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.Settings;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * WF-06 site / sensor reminders evaluated inside the cycle (ADR-051; CP-02 §8.10; CP-00A R-70 / D-037).
 *
 * <ul>
 *   <li>Due instants T-3: {@code due24hAt = lastChange + lifetime − 24 h}, {@code dueAt = lastChange + lifetime}.</li>
 *   <li>A new change entry (new {@code basedOnEntryId}) cancels the old reminders (DS:A-26) and is planned with the
 *       first-sight rule D-037: at most one notice — the latest passed stage, DUE_NOW only if {@code dueAt ≥ now − 24 h}.</li>
 *   <li>Otherwise a stage is posted once when its instant has passed; when both passed in the same tick only DUE_NOW
 *       is posted (same notification id — assumption A-9).</li>
 *   <li>The state is persisted before posting (no duplicate after a store failure); „Alarms” off does not apply (D-022).</li>
 * </ul>
 */
final class ReminderPlanner {

    private static final Duration FIRST_SIGHT_DUE_NOW_MAX_AGE = Duration.ofHours(24);

    private final EntryStore entries;
    private final ReminderStateStore store;
    private final ReminderScheduler scheduler;
    private final ConsumableCalculator calculator = new ConsumableCalculator();

    ReminderPlanner(EntryStore entries, ReminderStateStore store, ReminderScheduler scheduler) {
        this.entries = entries;
        this.store = store;
        this.scheduler = scheduler;
    }

    /**
     * Plans both consumables; a store failure of one never stops the other or the tick.
     *
     * @return {@code true} when a store call failed (R-20 → STORAGE_ERROR)
     */
    boolean run(Instant now, Settings settings) {
        boolean siteFailed = !plan(ConsumableKind.SITE, EntryType.SITE_CHANGE, settings.siteLifetimeDays(), now);
        boolean sensorFailed = !plan(ConsumableKind.SENSOR, EntryType.SENSOR_CHANGE, settings.sensorLifetimeDays(), now);
        return siteFailed || sensorFailed;
    }

    /** Returns {@code false} on a store failure. */
    private boolean plan(ConsumableKind kind, EntryType type, int lifetimeDays, Instant now) {
        try {
            Optional<Entry> change = entries.latestOfType(type);
            if (change.isEmpty() || change.get().time() == null || change.get().id() == null) {
                return true;
            }
            Entry e = change.get();
            List<Instant> times = calculator.reminderTimes(e.time(), lifetimeDays);
            Instant due24 = times.get(0);
            Instant due = times.get(1);
            Optional<ReminderState> current = store.get(kind);
            ReminderStage stage;
            ReminderState next;
            if (current.isEmpty() || !current.get().basedOnEntryId().equals(e.id())) {
                if (current.isPresent()) {
                    cancel(kind);
                }
                boolean n24 = false;
                boolean nAt = false;
                stage = null;
                if (!now.isBefore(due)) {
                    n24 = true;
                    nAt = true;
                    if (!due.isBefore(now.minus(FIRST_SIGHT_DUE_NOW_MAX_AGE))) {
                        stage = ReminderStage.DUE_NOW;
                    }
                } else if (!now.isBefore(due24)) {
                    n24 = true;
                    stage = ReminderStage.DUE_TOMORROW;
                }
                next = new ReminderState(kind, e.id(), due24, due, n24, nAt);
            } else {
                ReminderState s = current.get();
                boolean n24 = s.notified24h();
                boolean nAt = s.notifiedAt();
                if (!due24.equals(s.due24hAt()) || !due.equals(s.dueAt())) {
                    // T-4 recompute (lifetime or time changed): a stage moved into the future is armed again
                    n24 = n24 && !now.isBefore(due24);
                    nAt = nAt && !now.isBefore(due);
                }
                stage = null;
                if (!nAt && !now.isBefore(due)) {
                    stage = ReminderStage.DUE_NOW;
                    nAt = true;
                    n24 = true;
                } else if (!n24 && !now.isBefore(due24)) {
                    stage = ReminderStage.DUE_TOMORROW;
                    n24 = true;
                }
                next = new ReminderState(kind, e.id(), due24, due, n24, nAt);
                if (next.equals(s)) {
                    return true;
                }
            }
            store.put(next);
            if (stage != null) {
                post(new ReminderNotice(kind, stage, e.id(), Duration.between(e.time(), now), lifetimeDays));
            }
            return true;
        } catch (StorageException ex) {
            // retried next tick; the store already logged STORAGE_ERROR (R-13)
            return false;
        }
    }

    private void post(ReminderNotice notice) {
        try {
            scheduler.post(notice);
        } catch (RuntimeException ex) {
            // WF-06: failure (notifications off) → nothing; the S-01 counter still shows the status
        }
    }

    private void cancel(ConsumableKind kind) {
        try {
            scheduler.cancel(kind);
        } catch (RuntimeException ex) {
            // nothing visible to cancel
        }
    }
}
