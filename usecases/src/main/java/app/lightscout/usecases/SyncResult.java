package app.lightscout.usecases;

import app.lightscout.domain.port.FetchOutcome;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of one sync tick or re-evaluation (CP-00A R-10; 06 F12).
 *
 * @param outcome             classified outcome of the tick (re-evaluation: the stored last outcome)
 * @param newReading          {@code true} when this tick moved {@code lastReadingTime} forward
 * @param lastReadingTime     newest reading time ≤ now + 10 min (M-13); nullable
 * @param nextEvaluation      {@code AlarmDecision.nextEvaluation}; nullable
 * @param ringing             an alarm rings — CP-08 arms the heartbeat while true (R-35)
 * @param arrivalLagMedianSec rolling median arrival lag, 15–180 s (R-69)
 * @param noDataMinutes       the data-loss threshold setting in minutes
 */
public record SyncResult(FetchOutcome outcome, boolean newReading, Instant lastReadingTime,
                         Instant nextEvaluation, boolean ringing,
                         int arrivalLagMedianSec, int noDataMinutes) {

    /**
     * Creates the result.
     *
     * @throws NullPointerException if {@code outcome} is null
     */
    public SyncResult {
        Objects.requireNonNull(outcome, "outcome");
    }
}
