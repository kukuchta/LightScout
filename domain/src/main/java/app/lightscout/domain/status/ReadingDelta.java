package app.lightscout.domain.status;

import app.lightscout.domain.model.Reading;
import java.time.Duration;
import java.util.List;

/** Glucose delta rule shared by Home and the status notification: current minus the previous distinct reading. */
public final class ReadingDelta {

    /** Shortest accepted gap to the predecessor. */
    public static final Duration MIN_GAP = Duration.ofMinutes(4);
    /** Longest accepted gap to the predecessor. */
    public static final Duration MAX_GAP = Duration.ofMinutes(6);

    private ReadingDelta() {
    }

    /**
     * Returns current glucose minus its immediate distinct-time predecessor when their gap is 4–6 minutes.
     * A nearer reading outside that interval prevents searching farther back.
     *
     * @param current         the current reading; nullable
     * @param cachedReadings  readings that may precede it; nullable
     * @return the delta in mg/dL, or {@code null}
     */
    public static Integer calculate(Reading current, List<Reading> cachedReadings) {
        if (current == null || current.time() == null || cachedReadings == null) {
            return null;
        }
        Reading predecessor = null;
        for (Reading candidate : cachedReadings) {
            if (candidate == null || candidate.time() == null || !candidate.time().isBefore(current.time())) {
                continue;
            }
            if (predecessor == null || candidate.time().isAfter(predecessor.time())) {
                predecessor = candidate;
            }
        }
        if (predecessor == null) {
            return null;
        }
        Duration gap = Duration.between(predecessor.time(), current.time());
        return gap.compareTo(MIN_GAP) >= 0 && gap.compareTo(MAX_GAP) <= 0
                ? current.sgv() - predecessor.sgv() : null;
    }
}
