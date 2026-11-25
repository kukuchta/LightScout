package app.lightscout.domain.monitor;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.port.FetchOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Monitor state (ENT-07, IF-13, CP-00A R-6). Written only on {@code monitorExecutor}.
 *
 * @param monitoringSince      first tick after start / reset; first-use and watchdog anchor; nullable
 * @param lastSuccessfulCheck  last tick with outcome OK; nullable
 * @param lastCheckCompleted   end of the last tick, any outcome; nullable
 * @param lastOutcome          outcome of the last tick
 * @param connectionProblem    sticky NOT_NIGHTSCOUT / PERMISSION_MISSING / PERMISSION_WRITE_MISSING / SERVER_TOO_OLD (R-67); nullable
 * @param lastReadingTime      max reading time not after now + 10 min (M-13); nullable
 * @param arrivalLagMedianSec  rolling median arrival lag, initial 60, bounded 15–180
 * @param recentLagSec         unmodifiable last 12 lag samples
 * @param lastDailyMaintenance last daily maintenance; nullable
 * @param cursors              unmodifiable sync cursors per collection
 */
public record MonitorState(Instant monitoringSince,
                           Instant lastSuccessfulCheck, Instant lastCheckCompleted,
                           FetchOutcome lastOutcome,
                           FetchOutcome connectionProblem,
                           Instant lastReadingTime,
                           int arrivalLagMedianSec,
                           List<Integer> recentLagSec,
                           Instant lastDailyMaintenance,
                           Map<NsCollection, CollectionCursor> cursors) {

    /** Initial arrival-lag median in seconds (06 §4). */
    private static final int INITIAL_LAG_MEDIAN_SEC = 60;

    /**
     * Creates a monitor state; collections are copied unmodifiably.
     *
     * @throws NullPointerException if a non-nullable component or a collection element is null
     */
    public MonitorState {
        Objects.requireNonNull(lastOutcome, "lastOutcome");
        recentLagSec = List.copyOf(Objects.requireNonNull(recentLagSec, "recentLagSec"));
        cursors = Map.copyOf(Objects.requireNonNull(cursors, "cursors"));
    }

    /**
     * Returns the state before the first tick: all instants null, last outcome {@link FetchOutcome#OK},
     * no connection problem, lag median 60 s, no samples, no cursors.
     *
     * @return the initial state
     */
    public static MonitorState initial() {
        return new MonitorState(null, null, null, FetchOutcome.OK, null, null,
                INITIAL_LAG_MEDIAN_SEC, List.of(), null, Map.of());
    }
}
