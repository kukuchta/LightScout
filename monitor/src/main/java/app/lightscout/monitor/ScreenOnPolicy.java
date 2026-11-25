package app.lightscout.monitor;

import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.status.StatusSnapshot;

import java.time.Duration;
import java.time.Instant;

/**
 * „Screen-on with stale data → immediate tick” (06 §4, CP-00A R-37): tick when there is no reading, the data-loss
 * kind is not NONE, or the latest reading is at least 5 min old. Pure.
 */
final class ScreenOnPolicy {

    /** Age from which data counts as stale on screen-on (R-37). */
    static final Duration STALE_AGE = Duration.ofMinutes(5);

    /**
     * Decides.
     *
     * @param snapshot current snapshot (nullable before the first projection)
     * @param now      now
     * @return {@code true} when a {@code SCREEN_ON_STALE} tick is due
     */
    boolean shouldTick(StatusSnapshot snapshot, Instant now) {
        if (snapshot == null || snapshot.latest() == null) {
            return true;
        }
        if (snapshot.dataLoss() != null && snapshot.dataLoss().kind() != DataLossKind.NONE) {
            return true;
        }
        return !Duration.between(snapshot.latest().time(), now).minus(STALE_AGE).isNegative();
    }
}
