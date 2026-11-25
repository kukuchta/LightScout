package app.lightscout.usecases.sync;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.PumpAlertNotifier;
import app.lightscout.domain.port.StorageException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * WF-07 pump alerts inside the cycle (ADR-051; CP-02 §8.10): a PUMP_ALERT entry whose id is not in ENT-10 and whose
 * age is ≤ 1 h is posted (IF-17b) and its id added; older ones stay chart markers only. The id is recorded before
 * posting (no duplicate after a store failure). „Alarms” off does not apply (D-022).
 */
final class PumpAlertPlanner {

    /** WF-07 maximum age for a notification. */
    static final Duration MAX_AGE = Duration.ofHours(1);

    private final EntryStore entries;
    private final NotifiedAlertStore notified;
    private final PumpAlertNotifier notifier;

    PumpAlertPlanner(EntryStore entries, NotifiedAlertStore notified, PumpAlertNotifier notifier) {
        this.entries = entries;
        this.notified = notified;
        this.notifier = notifier;
    }

    /**
     * Posts every new, fresh pump alert.
     *
     * @return {@code true} when a store call failed (R-20 → STORAGE_ERROR)
     */
    boolean run(Instant now) {
        boolean failed = false;
        List<Entry> candidates;
        try {
            candidates = new ArrayList<>(entries.range(now.minus(MAX_AGE), now.plus(ChangeSync.FUTURE_TOLERANCE)));
        } catch (StorageException e) {
            return true;
        }
        candidates.sort(Comparator.comparing(Entry::time, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Entry e : candidates) {
            if (e.type() != EntryType.PUMP_ALERT || e.time() == null || e.id() == null
                    || Duration.between(e.time(), now).compareTo(MAX_AGE) > 0) {
                continue;
            }
            try {
                if (notified.contains(e.id())) {
                    continue;
                }
                notified.add(e.id(), e.time());
            } catch (StorageException ex) {
                failed = true;
                continue;
            }
            try {
                notifier.post(e);
            } catch (RuntimeException ex) {
                // notifications off → nothing (the chart marker remains)
            }
        }
        return failed;
    }
}
