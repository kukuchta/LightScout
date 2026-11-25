package app.lightscout.domain.entry;

import app.lightscout.domain.model.Reading;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reading rules shared by CP-02, CP-11 and CP-16 (04 §4.3 DR-1, 04 §8 M-9; CP-00A R-9). One implementation.
 * Null elements and readings without a time are ignored.
 */
public final class ReadingRules {

    /** M-9: readings dated more than this after now are future-dated. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(10);

    /** DR-1: readings closer than this count once (the earlier one). */
    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(60);

    private ReadingRules() {
    }

    /**
     * Tells whether a reading is future-dated (M-9).
     *
     * @param r   the reading
     * @param now evaluation instant
     * @return {@code true} when {@code r.time > now + 10 min}
     */
    public static boolean isFutureDated(Reading r, Instant now) {
        return r.time().isAfter(now.plus(FUTURE_TOLERANCE));
    }

    /**
     * Returns the readings to display or count: future-dated readings dropped, sorted by time ascending,
     * DR-1 deduplicated (a reading less than 60 s after the last kept one is dropped — the earlier is kept).
     *
     * @param readings readings in any order
     * @param now      evaluation instant
     * @return a new unmodifiable list
     */
    public static List<Reading> displayable(List<Reading> readings, Instant now) {
        List<Reading> sorted = new ArrayList<>();
        for (Reading r : readings) {
            if (r != null && r.time() != null && !isFutureDated(r, now)) {
                sorted.add(r);
            }
        }
        sorted.sort(Comparator.comparing(Reading::time));
        List<Reading> kept = new ArrayList<>(sorted.size());
        Instant lastKept = null;
        for (Reading r : sorted) {
            if (lastKept == null || Duration.between(lastKept, r.time()).compareTo(DEDUP_WINDOW) >= 0) {
                kept.add(r);
                lastKept = r.time();
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Returns the latest reading that is not future-dated (M-9, M-13).
     *
     * @param readings readings in any order
     * @param now      evaluation instant
     * @return the reading with the greatest time not after {@code now + 10 min}, if any
     */
    public static Optional<Reading> latest(List<Reading> readings, Instant now) {
        Reading best = null;
        for (Reading r : readings) {
            if (r != null && r.time() != null && !isFutureDated(r, now)
                    && (best == null || r.time().isAfter(best.time()))) {
                best = r;
            }
        }
        return Optional.ofNullable(best);
    }
}
