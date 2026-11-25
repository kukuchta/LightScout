package app.lightscout.monitor;

import app.lightscout.domain.monitor.CheckSchedule;
import app.lightscout.usecases.SyncResult;

import java.time.Duration;
import java.time.Instant;

/**
 * Sync schedule calculator (CP-08 §8.2 = 06 §4 + F12, M-13). Pure: no clock, no Android.
 *
 * <ul>
 *   <li>Expected time {@code E = lastReading + 5 min}.</li>
 *   <li>Arrival window: first tick at {@code E + lag₇₅ + 5 s} (server lag, clamped 15–180 s, initial 60 s; D-152), then every
 *       60 s until a new reading or {@code E + 5 min}.</li>
 *   <li>Overdue ({@code E + 5 min} … threshold): every 2 min, never past the threshold instant.</li>
 *   <li>Data loss (after the threshold): every 5 min.</li>
 *   <li>No reading at all (Q-58): the same 2 min / 5 min phases anchored on {@code monitoringSince} (R-61).</li>
 *   <li>F12: next tick = {@code min(schedule, AlarmDecision.nextEvaluation)}; an evaluation instant that fell due
 *       while the tick was still running (after its start, at or before its end) is armed at once (BUG-18 N1).</li>
 * </ul>
 */
final class TickSchedule {

    /** Reading cadence (EV-02). */
    static final Duration READING_INTERVAL = CheckSchedule.READING_INTERVAL;
    /** Arrival-window lead before the expected arrival. */
    static final Duration WINDOW_MARGIN = CheckSchedule.WINDOW_MARGIN;
    /** Arrival-window cadence. */
    static final Duration WINDOW_CADENCE = CheckSchedule.WINDOW_CADENCE;
    /** Length of the arrival window after {@code E}. */
    static final Duration WINDOW_LENGTH = CheckSchedule.WINDOW_LENGTH;
    /** Overdue cadence. */
    static final Duration OVERDUE_CADENCE = CheckSchedule.OVERDUE_CADENCE;
    /** Data-loss cadence. */
    static final Duration DATA_LOSS_CADENCE = CheckSchedule.DATA_LOSS_CADENCE;
    /** Cadence after an unexpected tick failure (no {@link SyncResult}); same as overdue (F13 quota). */
    static final Duration FAILURE_CADENCE = Duration.ofMinutes(2);
    /** M-13 future-dated limit for the schedule anchor. */
    static final Duration FUTURE_LIMIT = CheckSchedule.FUTURE_LIMIT;
    /** Lag bounds and initial value (06 §4). */
    static final int LAG_MIN_SEC = CheckSchedule.LAG_MIN_SEC;
    static final int LAG_MAX_SEC = CheckSchedule.LAG_MAX_SEC;
    static final int LAG_INITIAL_SEC = CheckSchedule.LAG_INITIAL_SEC;

    /**
     * Computes the next tick after a completed tick or re-evaluation that started and ended at {@code now}.
     *
     * @param now             end of the tick
     * @param result          the tick result
     * @param monitoringSince first-use anchor (R-61), used only while there is no reading; nullable
     * @return the plan (never earlier than {@code now})
     */
    TickPlan next(Instant now, SyncResult result, Instant monitoringSince) {
        return next(now, now, result, monitoringSince);
    }

    /**
     * Computes the next tick after a completed tick or re-evaluation.
     *
     * <p>BUG-18 N1: the evaluation inside the tick happens near {@code started}, but {@code now} is the end of the
     * tick (after the post-publish maintenance / backfill, or a slow network). A {@code nextEvaluation} in
     * {@code (started, now]} (snooze end, data-loss crossing, F12 re-check) fell due meanwhile and is armed at
     * {@code now}. This cannot loop: the evaluation that produced it ran at or after {@code started} and before
     * the instant, and the follow-up tick evaluates at or after it, so its own {@code nextEvaluation} is later. A
     * {@code nextEvaluation ≤ started} was already handled by this evaluation and is dropped (A-3).
     *
     * @param started         start of the tick (no later than its evaluation)
     * @param now             end of the tick
     * @param result          the tick result
     * @param monitoringSince first-use anchor (R-61), used only while there is no reading; nullable
     * @return the plan (never earlier than {@code now})
     */
    TickPlan next(Instant started, Instant now, SyncResult result, Instant monitoringSince) {
        TickPlan schedule = schedule(now, result.lastReadingTime(), monitoringSince, result.arrivalLagMedianSec(),
                Duration.ofMinutes(result.noDataMinutes()));
        Instant evaluation = result.nextEvaluation();
        Instant after = started.isAfter(now) ? now : started;
        if (evaluation != null && evaluation.isAfter(after) && evaluation.isBefore(schedule.at())) {
            return new TickPlan(evaluation.isAfter(now) ? evaluation : now, TickPhase.EVALUATION);
        }
        return schedule;
    }

    /**
     * Plan used when the tick failed unexpectedly and no result exists.
     *
     * @param now now
     * @return a retry plan
     */
    TickPlan afterFailure(Instant now) {
        return new TickPlan(now.plus(FAILURE_CADENCE), TickPhase.RETRY);
    }

    /**
     * The 06 §4 schedule without F12.
     *
     * @param now             now
     * @param lastReadingTime newest reading time (nullable); ignored when later than {@code now + 10 min} (M-13)
     * @param monitoringSince first-use anchor (R-61) for the no-reading case; nullable
     * @param lagMedianSec    rolling median arrival lag in seconds
     * @param threshold       data-loss threshold
     * @return the plan
     */
    TickPlan schedule(Instant now, Instant lastReadingTime, Instant monitoringSince, int lagMedianSec,
                      Duration threshold) {
        return toTick(CheckSchedule.plan(now, lastReadingTime, monitoringSince, lagMedianSec, threshold));
    }

    /**
     * No reading at all (Q-58): the 06 §4 phases on the first-use anchor {@code monitoringSince} (R-61) — every
     * 2 min (landing on {@code monitoringSince + threshold}) until the threshold, then every 5 min. Without an anchor
     * (monitoring not started yet) every 2 min.
     *
     * @param now             now
     * @param monitoringSince anchor; nullable
     * @param threshold       data-loss threshold
     * @return the plan
     */
    TickPlan firstUse(Instant now, Instant monitoringSince, Duration threshold) {
        return toTick(CheckSchedule.firstUse(now, monitoringSince, threshold));
    }

    private static TickPlan toTick(CheckSchedule.Plan plan) {
        return new TickPlan(plan.at(), TickPhase.valueOf(plan.phase().name()));
    }

    /**
     * Clamps the lag to 15–180 s; a non-positive value is treated as unknown (initial 60 s).
     *
     * @param lagSec lag
     * @return clamped lag
     */
    static int clampLag(int lagSec) {
        return CheckSchedule.clampLag(lagSec);
    }
}
