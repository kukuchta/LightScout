package app.lightscout.domain.alarm;

import java.time.Duration;
import java.time.Instant;

/**
 * Watchdog policy „LightScout is not running” (CMP-04, IF-03; DS:D-033, ADR-045, 06 §11 F2; CP-00A R-68).
 *
 * <p>Keyed to {@code lastCheckCompleted} (end of every tick, any outcome), not to the last successful check:
 * an outage with a live loop raises No data only. Per R-68 the caller passes {@code monitoringSince} when
 * {@code lastCheckCompleted} is absent; with no anchor at all monitoring never started and the result is false.
 */
public final class WatchdogPolicy {

    /**
     * Creates the stateless rule component.
     */
    public WatchdogPolicy() {
        // no state
    }

    /**
     * Tells whether no check has completed for the threshold.
     *
     * @param now                evaluation instant
     * @param lastCheckCompleted last completed check (or {@code monitoringSince}, R-68); nullable
     * @param threshold          data-loss threshold
     * @return {@code true} when {@code now ≥ lastCheckCompleted + threshold}
     */
    public boolean isNotRunning(Instant now, Instant lastCheckCompleted, Duration threshold) {
        if (lastCheckCompleted == null) {
            return false;
        }
        return !now.isBefore(lastCheckCompleted.plus(threshold));
    }

    /**
     * DCR-36 (D-153): tells whether a stale watchdog is explained by the phone having been off, so that it is not a
     * stopped loop. True only in the first {@link #BOOT_WINDOW} after the system start, and only while the anchor
     * still lies before that start by more than {@link #BOOT_MARGIN}. Any evaluation in the window may use it
     * (the first one to run is not necessarily the {@code BOOT} tick); once a check has completed the anchor is
     * after the boot, and after the window a loop that completed nothing rings again.
     *
     * @param lastCheckCompleted anchor of the watchdog (R-68); nullable
     * @param now                evaluation instant
     * @param sinceBoot          time since the system started ({@code Clock.elapsedRealtimeMs}); negative is unknown
     * @return {@code true} when the anchor predates this boot and the boot is recent
     */
    public boolean explainedByPowerOff(Instant lastCheckCompleted, Instant now, Duration sinceBoot) {
        if (lastCheckCompleted == null || now == null || sinceBoot == null || sinceBoot.isNegative()
                || sinceBoot.compareTo(BOOT_WINDOW) >= 0) {
            return false;
        }
        return lastCheckCompleted.isBefore(now.minus(sinceBoot).minus(BOOT_MARGIN));
    }

    /** Tolerance for the wall-clock error of the boot instant (the clock may be corrected after start). */
    static final Duration BOOT_MARGIN = Duration.ofSeconds(60);

    /** How long after the system start a stale anchor from before the boot is attributed to the phone being off. */
    static final Duration BOOT_WINDOW = Duration.ofMinutes(10);
}
