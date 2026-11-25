package app.lightscout.usecases.status;

import app.lightscout.domain.entry.ReadingRules;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.ReadingStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reading lookups shared by the tick, the re-evaluation and the status projection (04 §8 M-9 amended, M-13).
 * Store reads may throw {@link app.lightscout.domain.port.StorageException}; the in-memory readings of the
 * current tick are merged so that a failed store still feeds data-loss detection and alarms (WF-01 step 4).
 */
public final class ReadingQueries {

    /** M-9: readings dated after now + 10 min are future-dated. */
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(10);

    /** Practical upper bound when asking the store for future-dated readings (assumption A-6 in SS-05). */
    static final Duration FUTURE_HORIZON = Duration.ofDays(366);

    private ReadingQueries() {
    }

    /**
     * Returns the newest non-future reading (time ≤ now + 10 min) of the store and of {@code fetched}.
     *
     * @param store   reading store; may throw
     * @param now     evaluation instant
     * @param fetched readings fetched in this tick (may be empty)
     * @return the latest reading or {@code null}
     */
    public static Reading latest(ReadingStore store, Instant now, List<Reading> fetched) {
        Reading best = ReadingRules.latest(fetched, now).orElse(null);
        Optional<Reading> stored = store.latestNotAfter(now.plus(FUTURE_TOLERANCE));
        if (stored.isPresent() && stored.get().time() != null
                && (best == null || stored.get().time().isAfter(best.time()))) {
            best = stored.get();
        }
        return best;
    }

    /**
     * Returns the newest future-dated reading (time &gt; now + 10 min) with {@code sgv ≤ lowMgdl}; it is passed
     * to the evaluator as age 0 (M-9 amended).
     *
     * @param store   reading store; may throw
     * @param now     evaluation instant
     * @param lowMgdl Low threshold
     * @param fetched readings fetched in this tick (may be empty)
     * @return the reading or {@code null}
     */
    public static Reading futureDatedLow(ReadingStore store, Instant now, int lowMgdl, List<Reading> fetched) {
        List<Reading> candidates = new ArrayList<>(fetched);
        Instant from = now.plus(FUTURE_TOLERANCE).plusMillis(1);
        candidates.addAll(store.range(from, now.plus(FUTURE_HORIZON)));
        return futureDatedLow(candidates, now, lowMgdl);
    }

    /**
     * Pure variant over given readings (used with this tick's fetched readings when the store fails — IR-37).
     *
     * @param candidates readings
     * @param now        evaluation instant
     * @param lowMgdl    Low threshold
     * @return the newest future-dated reading with {@code sgv ≤ lowMgdl}, or {@code null}
     */
    public static Reading futureDatedLow(List<Reading> candidates, Instant now, int lowMgdl) {
        Reading best = null;
        for (Reading r : candidates) {
            if (r != null && r.time() != null && ReadingRules.isFutureDated(r, now) && r.sgv() <= lowMgdl
                    && (best == null || r.time().isAfter(best.time()))) {
                best = r;
            }
        }
        return best;
    }
}
