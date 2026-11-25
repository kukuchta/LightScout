package app.lightscout.usecases.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Arrival lag of readings (06 §4; CP-00A R-69 (2), amended by D-152).
 *
 * <p>A sample is the time the <em>server</em> had the reading: {@code srvModified − reading.time}, one per newly
 * stored reading that arrived as a change. It does not depend on when the monitor looked, so it is not censored by
 * the schedule (D-152: samples taken as {@code tickStart − reading.time} always followed the planned first check
 * and the tick lateness, and pinned the median at its 180 s clamp while Nightscout had the data after ~35 s). The
 * last 12 samples are kept; the value used by the schedule is their 75th percentile, bounded to 15–180 s, so the
 * first check lands after most arrivals; 60 s while no sample exists.
 */
final class LagEstimator {

    static final int MAX_SAMPLES = 12;
    static final int MIN_SEC = 15;
    static final int MAX_SEC = 180;
    /** Samples above this are an uploader catching up after a gap, not the usual lag. */
    static final int MAX_SAMPLE_SEC = 600;
    /** Percentile of the samples used for the schedule. */
    static final int PERCENTILE = 75;

    private LagEstimator() {
    }

    /**
     * One sample from the server time of a reading.
     *
     * @param readingTimeMs   the reading timestamp (epoch ms)
     * @param srvModifiedMs   when the server stored it (epoch ms)
     * @return the lag in whole seconds, or empty for a clock skew (negative) or a catch-up upload (&gt; 10 min)
     */
    static OptionalInt sample(long readingTimeMs, long srvModifiedMs) {
        long ms = srvModifiedMs - readingTimeMs;
        if (ms < 0 || ms > MAX_SAMPLE_SEC * 1000L) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) (ms / 1000L));
    }

    /** New sample list: old samples followed by new ones, trimmed to the last {@link #MAX_SAMPLES}. */
    static List<Integer> addSamples(List<Integer> recent, List<Integer> samples) {
        List<Integer> all = new ArrayList<>(recent);
        all.addAll(samples);
        int from = Math.max(0, all.size() - MAX_SAMPLES);
        return List.copyOf(all.subList(from, all.size()));
    }

    /** The scheduling lag: the {@link #PERCENTILE}th percentile bounded to 15–180 s; {@code fallback} without samples. */
    static int median(List<Integer> samples, int fallback) {
        if (samples.isEmpty()) {
            return fallback;
        }
        List<Integer> sorted = new ArrayList<>(samples);
        sorted.sort(null);
        int rank = (int) Math.ceil(PERCENTILE / 100.0 * sorted.size());   // nearest rank, 1-based
        int value = sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
        return Math.max(MIN_SEC, Math.min(MAX_SEC, value));
    }
}
