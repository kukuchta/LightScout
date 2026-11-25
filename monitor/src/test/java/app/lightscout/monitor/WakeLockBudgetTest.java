package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * IR-73 / Q-65: each acquisition ≤ 30 s, refreshes within a 120 s hard cap per session.
 */
public class WakeLockBudgetTest {

    @Test
    public void noSession_noAcquisition() {
        WakeLockBudget b = new WakeLockBudget();
        assertFalse(b.active());
        assertEquals(0L, b.nextTimeoutMs(1_000L));
    }

    @Test
    public void eachAcquisition_atMost30s() {
        WakeLockBudget b = new WakeLockBudget();
        b.start(1_000L);
        assertTrue(b.active());
        assertEquals(30_000L, b.nextTimeoutMs(1_000L));
        assertEquals(30_000L, b.nextTimeoutMs(1_000L + 60_000L));
    }

    @Test
    public void lastAcquisition_endsAtThe120sCap_thenNothing() {
        WakeLockBudget b = new WakeLockBudget();
        b.start(0L);
        assertEquals(20_000L, b.nextTimeoutMs(100_000L));
        assertEquals(0L, b.nextTimeoutMs(120_000L));
        assertEquals(0L, b.nextTimeoutMs(200_000L));
    }

    @Test
    public void endedSession_noMoreAcquisitions() {
        WakeLockBudget b = new WakeLockBudget();
        b.start(0L);
        b.end();
        assertFalse(b.active());
        assertEquals(0L, b.nextTimeoutMs(10L));
    }

    @Test
    public void session_refreshesWithinCap_releaseCancelsTimerAndReleases() {
        long[] now = {0L};
        java.util.List<Long> timeouts = new java.util.ArrayList<>();
        boolean[] held = {false};
        boolean[] cancelled = {false};
        Runnable[] task = new Runnable[1];
        WakeLockSession s = new WakeLockSession(new WakeLockSession.Handle() {
            @Override
            public void acquire(long timeoutMs) {
                timeouts.add(timeoutMs);
                held[0] = true;
            }

            @Override
            public void release() {
                held[0] = false;
            }

            @Override
            public boolean isHeld() {
                return held[0];
            }
        }, (t, p) -> {
            task[0] = t;
            return () -> cancelled[0] = true;
        }, () -> now[0]);
        s.acquire();
        now[0] = 100_000L;
        task[0].run();                                 // timer refresh near the cap
        now[0] = 130_000L;
        s.refresh();                                   // cap spent: no acquisition
        assertEquals(java.util.List.of(30_000L, 20_000L), timeouts);
        s.release();
        assertTrue(cancelled[0]);
        assertFalse(held[0]);
    }
}
