package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * CP-08 §9 „JVM — watchdog arming (F2)” — the pure rule.
 */
public class WatchdogScheduleTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(20);
    private final WatchdogSchedule rule = new WatchdogSchedule();
    private final Instant now = Fakes.T0;

    @Test
    public void armedAtLastCheckCompletedPlusThreshold() {
        Instant lcc = now.minusSeconds(5);
        assertEquals(lcc.plus(THRESHOLD), rule.armAt(now, lcc, THRESHOLD, null));
    }

    @Test
    public void stale_notSnoozed_rearmedNowPlus5Min() {
        Instant lcc = now.minus(Duration.ofMinutes(30));
        assertEquals(now.plus(Duration.ofMinutes(5)), rule.armAt(now, lcc, THRESHOLD, null));
    }

    @Test
    public void stale_snoozed_rearmedAtSnoozeEnd() {
        Instant lcc = now.minus(Duration.ofMinutes(30));
        Instant snoozedUntil = now.plus(Duration.ofMinutes(30));
        assertEquals(snoozedUntil, rule.armAt(now, lcc, THRESHOLD, snoozedUntil));
    }

    @Test
    public void expiredSnooze_counts_asNotSnoozed() {
        assertEquals(now.plus(Duration.ofMinutes(5)), rule.afterFire(now, now.minusSeconds(1)));
    }

    @Test
    public void exactlyAtThreshold_isStale() {
        Instant lcc = now.minus(THRESHOLD);
        assertEquals(now.plus(Duration.ofMinutes(5)), rule.armAt(now, lcc, THRESHOLD, null));
    }

    @Test
    public void noAnchor_monitoringNeverStarted_notArmed() {
        assertNull(rule.armAt(now, null, THRESHOLD, null));
    }
}
