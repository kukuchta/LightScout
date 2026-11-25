package app.lightscout.domain.status;

import app.lightscout.domain.monitor.CheckSchedule;
import app.lightscout.domain.port.FetchOutcome;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The cycle between two consecutive checks, for the Home progress bar (D-151): from the end of the last check to the
 * next planned one ({@link CheckSchedule}), and how the user should read it. Display only: it never changes the
 * overdue, alarm or data-loss semantics.
 *
 * @param start the end of the last completed check
 * @param next  the next planned check; never before {@code start}
 * @param kind  how to present the cycle
 */
public record CheckCycle(Instant start, Instant next, Kind kind) {

    /** How a cycle reads to the user. */
    public enum Kind {
        /** Waiting for the first planned check after a fresh reading: the slow cycle. */
        NORMAL,
        /** The check found nothing new yet (first miss in the arrival window): a fast cycle, no alarm colour. */
        WAITING,
        /** The check failed, or found nothing twice in a row, or the reading is overdue: orange. */
        MISSED,
        /** No data for the data-loss threshold: grey. */
        NO_DATA
    }

    /** Tolerance when counting window checks from their completion times. */
    private static final Duration COUNT_SLACK = Duration.ofSeconds(30);

    /**
     * Creates a cycle.
     *
     * @throws NullPointerException if a component is null
     */
    public CheckCycle {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(kind, "kind");
        if (next.isBefore(start)) {
            next = start;
        }
    }

    /**
     * Builds the cycle of the last completed check.
     *
     * <p>The end is the schedule's plan made at {@code lastCheckCompleted}. The start of the slow cycle (before
     * the first window check) is when the server had the reading ({@code reading time + its lag}), not the end of
     * the last check: an extra check (opening Home refreshes) must not restart the bar. Later cycles run between
     * consecutive checks. The kind: an outcome other than OK or a
     * sticky connection problem is MISSED at once; otherwise the first window check without a new reading is
     * WAITING and the second (or any overdue check) is MISSED; data loss is NO_DATA.
     *
     * @param now                the projection instant
     * @param lastCheckCompleted end of the last tick, any outcome; nullable (no tick yet: no cycle)
     * @param lastOutcome        outcome of the last tick
     * @param connectionProblem  sticky connection problem; nullable
     * @param lastReadingTime    newest reading time; nullable
     * @param monitoringSince    first-use anchor; nullable
     * @param lagMedianSec       lag used by the schedule in seconds
     * @param lastLagSec         the newest lag sample in seconds (when the server had the newest reading); nullable
     * @param threshold          data-loss threshold
     * @return the cycle, or {@code null} while no check has completed
     */
    public static CheckCycle of(Instant now, Instant lastCheckCompleted, FetchOutcome lastOutcome,
                                FetchOutcome connectionProblem, Instant lastReadingTime, Instant monitoringSince,
                                int lagMedianSec, Integer lastLagSec, Duration threshold) {
        if (lastCheckCompleted == null) {
            return null;
        }
        CheckSchedule.Plan plan = CheckSchedule.plan(lastCheckCompleted, lastReadingTime, monitoringSince,
                lagMedianSec, threshold);
        Instant anchor = lastReadingTime != null && !lastReadingTime.isAfter(lastCheckCompleted.plus(
                CheckSchedule.FUTURE_LIMIT)) ? lastReadingTime : monitoringSince;
        boolean noData = anchor != null && !now.isBefore(anchor.plus(threshold));
        Kind kind;
        if (noData || plan.phase() == CheckSchedule.Phase.DATA_LOSS) {
            kind = Kind.NO_DATA;
        } else if (lastOutcome != FetchOutcome.OK || connectionProblem != null) {
            kind = Kind.MISSED;
        } else {
            kind = switch (plan.phase()) {
                case EXPECTED_READING -> Kind.NORMAL;
                case ARRIVAL_WINDOW -> windowChecks(lastCheckCompleted, lastReadingTime, lagMedianSec) >= 2
                        ? Kind.MISSED : Kind.WAITING;
                case OVERDUE, NO_READING, DATA_LOSS -> Kind.MISSED;
            };
        }
        Instant start = lastCheckCompleted;
        if (kind == Kind.NORMAL && lastReadingTime != null) {
            int lag = lastLagSec != null ? lastLagSec : CheckSchedule.clampLag(lagMedianSec);
            Instant arrival = lastReadingTime.plusSeconds(Math.max(0, lag));
            if (arrival.isBefore(lastCheckCompleted)) {
                start = arrival;
            }
        }
        return new CheckCycle(start, plan.at(), kind);
    }

    /** Window checks completed without a new reading, counted from the first window tick at 60 s steps. */
    private static long windowChecks(Instant lastCheckCompleted, Instant lastReadingTime, int lagMedianSec) {
        Instant first = CheckSchedule.windowStart(lastReadingTime, lagMedianSec);
        Duration since = Duration.between(first, lastCheckCompleted).plus(COUNT_SLACK);
        return 1 + Math.max(0L, since.toMillis() / CheckSchedule.WINDOW_CADENCE.toMillis());
    }
}
