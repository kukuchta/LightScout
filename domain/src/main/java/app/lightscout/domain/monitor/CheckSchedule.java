package app.lightscout.domain.monitor;

import java.time.Duration;
import java.time.Instant;

/**
 * The sync schedule of CP-08 §8.2 = 06 §4 without F12 (pure: no clock, no Android). One definition shared by the
 * monitor ({@code TickSchedule}) and the status projection ({@link app.lightscout.domain.status.CheckCycle}, D-151).
 *
 * <ul>
 *   <li>Expected time {@code E = lastReading + 5 min}.</li>
 *   <li>Arrival window: first tick at {@code E + lag₇₅ + 5 s} (lag clamped 15–180 s, initial 60 s; the lag is the
 *       time the server needed, D-152), then every 60 s until a new reading or {@code E + 5 min}.</li>
 *   <li>Overdue ({@code E + 5 min} … threshold): every 2 min, never past the threshold instant.</li>
 *   <li>Data loss (after the threshold): every 5 min.</li>
 *   <li>No reading at all (Q-58): the same 2 min / 5 min phases anchored on {@code monitoringSince} (R-61).</li>
 * </ul>
 */
public final class CheckSchedule {

    /** Reading cadence (EV-02). */
    public static final Duration READING_INTERVAL = Duration.ofMinutes(5);
    /** Margin after the expected arrival at which the first window check is made (D-152; was a 15 s lead). */
    public static final Duration WINDOW_MARGIN = Duration.ofSeconds(5);
    /** Arrival-window cadence. */
    public static final Duration WINDOW_CADENCE = Duration.ofSeconds(60);
    /** Length of the arrival window after {@code E}. */
    public static final Duration WINDOW_LENGTH = Duration.ofMinutes(5);
    /** Overdue cadence. */
    public static final Duration OVERDUE_CADENCE = Duration.ofMinutes(2);
    /** Data-loss cadence. */
    public static final Duration DATA_LOSS_CADENCE = Duration.ofMinutes(5);
    /** M-13 future-dated limit for the schedule anchor. */
    public static final Duration FUTURE_LIMIT = Duration.ofMinutes(10);
    /** Lag lower bound in seconds (06 §4). */
    public static final int LAG_MIN_SEC = 15;
    /** Lag upper bound in seconds (06 §4). */
    public static final int LAG_MAX_SEC = 180;
    /** Initial lag in seconds (06 §4). */
    public static final int LAG_INITIAL_SEC = 60;

    /** Which rule produced a plan. */
    public enum Phase {
        /** First tick of the arrival window, {@code E + lag₇₅ + 5 s}. */
        EXPECTED_READING,
        /** 60 s window cadence. */
        ARRIVAL_WINDOW,
        /** 2 min overdue cadence. */
        OVERDUE,
        /** 5 min cadence after the data-loss threshold. */
        DATA_LOSS,
        /** No reading yet: 2 min cadence before {@code monitoringSince + threshold} (Q-58). */
        NO_READING
    }

    /**
     * The next planned check.
     *
     * @param at    the instant
     * @param phase the rule
     */
    public record Plan(Instant at, Phase phase) {
    }

    private CheckSchedule() {
    }

    /**
     * Computes the next check.
     *
     * @param now             the instant the plan is made from (end of the last tick)
     * @param lastReadingTime newest reading time (nullable); ignored when later than {@code now + 10 min} (M-13)
     * @param monitoringSince first-use anchor (R-61) for the no-reading case; nullable
     * @param lagMedianSec    rolling median arrival lag in seconds
     * @param threshold       data-loss threshold
     * @return the plan
     */
    public static Plan plan(Instant now, Instant lastReadingTime, Instant monitoringSince, int lagMedianSec,
                            Duration threshold) {
        Instant anchor = lastReadingTime;
        if (anchor != null && anchor.isAfter(now.plus(FUTURE_LIMIT))) {
            anchor = null;
        }
        if (anchor == null) {
            return firstUse(now, monitoringSince, threshold);
        }
        Instant windowStart = windowStart(anchor, lagMedianSec);
        Instant windowEnd = anchor.plus(READING_INTERVAL).plus(WINDOW_LENGTH);
        Instant dataLoss = anchor.plus(threshold);
        if (now.isBefore(windowStart)) {
            return new Plan(windowStart, Phase.EXPECTED_READING);
        }
        if (now.isBefore(windowEnd)) {
            return new Plan(now.plus(WINDOW_CADENCE), Phase.ARRIVAL_WINDOW);
        }
        if (now.isBefore(dataLoss)) {
            Instant next = now.plus(OVERDUE_CADENCE);
            // Land on the threshold itself (QA-02 ≤ 10 s, F12 defence in depth — A-4).
            return new Plan(next.isAfter(dataLoss) ? dataLoss : next, Phase.OVERDUE);
        }
        return new Plan(now.plus(DATA_LOSS_CADENCE), Phase.DATA_LOSS);
    }

    /**
     * First tick of the arrival window for a reading: {@code reading + 5 min + lag₇₅ + 5 s}.
     *
     * @param readingTime  the newest reading time
     * @param lagMedianSec rolling median arrival lag in seconds
     * @return the instant
     */
    public static Instant windowStart(Instant readingTime, int lagMedianSec) {
        return readingTime.plus(READING_INTERVAL).plusSeconds(clampLag(lagMedianSec)).plus(WINDOW_MARGIN);
    }

    /**
     * No reading at all (Q-58): every 2 min (landing on {@code monitoringSince + threshold}) until the threshold,
     * then every 5 min. Without an anchor (monitoring not started yet) every 2 min.
     *
     * @param now             now
     * @param monitoringSince anchor; nullable
     * @param threshold       data-loss threshold
     * @return the plan
     */
    public static Plan firstUse(Instant now, Instant monitoringSince, Duration threshold) {
        if (monitoringSince == null) {
            return new Plan(now.plus(OVERDUE_CADENCE), Phase.NO_READING);
        }
        Instant dataLoss = monitoringSince.plus(threshold);
        if (now.isBefore(dataLoss)) {
            Instant next = now.plus(OVERDUE_CADENCE);
            return new Plan(next.isAfter(dataLoss) ? dataLoss : next, Phase.NO_READING);
        }
        return new Plan(now.plus(DATA_LOSS_CADENCE), Phase.DATA_LOSS);
    }

    /**
     * Clamps the lag to 15–180 s; a non-positive value is treated as unknown (initial 60 s).
     *
     * @param lagSec lag
     * @return clamped lag
     */
    public static int clampLag(int lagSec) {
        if (lagSec <= 0) {
            return LAG_INITIAL_SEC;
        }
        return Math.max(LAG_MIN_SEC, Math.min(LAG_MAX_SEC, lagSec));
    }
}
