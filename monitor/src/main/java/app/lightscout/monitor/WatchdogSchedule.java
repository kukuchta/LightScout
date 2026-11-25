package app.lightscout.monitor;

import java.time.Duration;
import java.time.Instant;

/**
 * Watchdog arming rule (ADR-045 + 06 §11 F2). Pure.
 *
 * <p>Armed at {@code lastCheckCompleted + threshold} after every tick, any outcome. When that instant is not in the
 * future (the loop is stale — NOT_RUNNING has fired or is about to), it is re-armed at the NOT_RUNNING
 * {@code snoozedUntil} if snoozed, else at {@code now + 5 min}.
 */
final class WatchdogSchedule {

    /** Re-arm interval while still stale (F2). */
    static final Duration STALE_REARM = Duration.ofMinutes(5);

    /**
     * Computes the watchdog instant.
     *
     * @param now                   now
     * @param anchor                {@code lastCheckCompleted}, else {@code monitoringSince} (R-68); nullable
     * @param threshold             data-loss threshold
     * @param notRunningSnoozedUntil NOT_RUNNING {@code snoozedUntil}; nullable
     * @return the instant, or {@code null} when monitoring never started (no anchor) — nothing to watch
     */
    Instant armAt(Instant now, Instant anchor, Duration threshold, Instant notRunningSnoozedUntil) {
        if (anchor == null) {
            return null;
        }
        Instant due = anchor.plus(threshold);
        if (due.isAfter(now)) {
            return due;
        }
        return afterFire(now, notRunningSnoozedUntil);
    }

    /**
     * Re-arm instant after NOT_RUNNING fired while still stale (F2).
     *
     * @param now                    now
     * @param notRunningSnoozedUntil NOT_RUNNING {@code snoozedUntil}; nullable
     * @return {@code snoozedUntil} when it is in the future, else {@code now + 5 min}
     */
    Instant afterFire(Instant now, Instant notRunningSnoozedUntil) {
        if (notRunningSnoozedUntil != null && notRunningSnoozedUntil.isAfter(now)) {
            return notRunningSnoozedUntil;
        }
        return now.plus(STALE_REARM);
    }
}
