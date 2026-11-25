package app.lightscout.domain.alarm;

import app.lightscout.domain.FakeClock;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TST-01 IF-03 rows (CP-01 §8.4 / §9; CP-00A R-68).
 */
public class WatchdogPolicyTest {

    private final FakeClock clock = new FakeClock(Instant.parse("2026-10-15T12:00:00Z"));
    private final WatchdogPolicy policy = new WatchdogPolicy();

    @Test
    public void lastCheck20MinAgo_threshold20_isNotRunning() {
        assertTrue(policy.isNotRunning(clock.now(), clock.now().minus(Duration.ofMinutes(20)), Duration.ofMinutes(20)));
    }

    @Test
    public void lastCheck19m59sAgo_threshold20_isRunning() {
        assertFalse(policy.isNotRunning(clock.now(), clock.now().minus(Duration.ofMinutes(19).plusSeconds(59)),
                Duration.ofMinutes(20)));
    }

    @Test
    public void threshold45_usesThreshold() {
        Duration threshold = Duration.ofMinutes(45);
        assertFalse(policy.isNotRunning(clock.now(), clock.now().minus(Duration.ofMinutes(44)), threshold));
        assertTrue(policy.isNotRunning(clock.now(), clock.now().minus(Duration.ofMinutes(45)), threshold));
    }

    @Test
    public void r68_noAnchor_monitoringNeverStarted_isFalse() {
        assertFalse(policy.isNotRunning(clock.now(), null, Duration.ofMinutes(20)));
    }

    // DCR-36 (D-153): a stale anchor from before the system start, shortly after it, is the phone being off

    private static final Duration FRESH_BOOT = Duration.ofSeconds(30);

    @Test
    public void dcr36_anchorWellBeforeTheBoot_inTheWindow_isExplainedByPowerOff() {
        Instant now = clock.now();
        assertTrue(policy.explainedByPowerOff(now.minus(Duration.ofHours(1)), now, FRESH_BOOT));
        // boot instant = now - 30 s; the anchor must be more than 60 s before it
        assertTrue(policy.explainedByPowerOff(now.minusSeconds(30 + 61), now, FRESH_BOOT));
    }

    @Test
    public void dcr36_anchorAtOrAfterTheBoot_isNotExplained() {
        Instant now = clock.now();
        assertFalse(policy.explainedByPowerOff(now.minusSeconds(30), now, FRESH_BOOT));
        assertFalse(policy.explainedByPowerOff(now.minusSeconds(30 + 60), now, FRESH_BOOT));   // clock tolerance
        assertFalse(policy.explainedByPowerOff(now.minusSeconds(10), now, FRESH_BOOT));
    }

    @Test
    public void dcr36_afterTheBootWindow_neverExplains() {
        Instant now = clock.now();
        Instant old = now.minus(Duration.ofHours(5));
        assertTrue(policy.explainedByPowerOff(old, now, Duration.ofMinutes(10).minusMillis(1)));
        assertFalse(policy.explainedByPowerOff(old, now, Duration.ofMinutes(10)));
        assertFalse(policy.explainedByPowerOff(old, now, Duration.ofHours(3)));
    }

    @Test
    public void dcr36_missingOrUnknownInputs_neverExplain() {
        Instant now = clock.now();
        assertFalse(policy.explainedByPowerOff(null, now, FRESH_BOOT));
        assertFalse(policy.explainedByPowerOff(now.minus(Duration.ofHours(1)), null, FRESH_BOOT));
        assertFalse(policy.explainedByPowerOff(now.minus(Duration.ofHours(1)), now, null));
        assertFalse(policy.explainedByPowerOff(now.minus(Duration.ofHours(1)), now, Duration.ofSeconds(-1)));
    }
}
