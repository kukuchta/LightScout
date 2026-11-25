package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class InteractiveStatusRefreshTest {

    @Test
    public void refreshesImmediatelyOnScreenOnAndEveryMinuteUntilScreenOff() {
        FakeTimer timer = new FakeTimer();
        AtomicInteger refreshes = new AtomicInteger();
        InteractiveStatusRefresh refresh = new InteractiveStatusRefresh(timer, refreshes::incrementAndGet);

        refresh.start(false);
        assertEquals(0, timer.scheduleCount);
        refresh.screenOn();
        assertEquals(1, refreshes.get());
        assertEquals(InteractiveStatusRefresh.INTERVAL_MS, timer.delayMs);
        timer.fire();
        assertEquals(2, refreshes.get());
        assertEquals(InteractiveStatusRefresh.INTERVAL_MS, timer.delayMs);
        refresh.screenOff();
        assertTrue(timer.cancelCount > 0);
        timer.fire();
        assertEquals(2, refreshes.get());
    }

    @Test
    public void interactiveAtStartGetsMinuteRefreshButNoImmediateRefresh() {
        FakeTimer timer = new FakeTimer();
        AtomicInteger refreshes = new AtomicInteger();
        InteractiveStatusRefresh refresh = new InteractiveStatusRefresh(timer, refreshes::incrementAndGet);
        refresh.start(true);

        assertEquals(0, refreshes.get());
        assertEquals(InteractiveStatusRefresh.INTERVAL_MS, timer.delayMs);
        timer.fire();
        assertEquals(1, refreshes.get());
    }

    private static final class FakeTimer implements InteractiveStatusRefresh.Timer {
        Runnable task;
        long delayMs;
        int scheduleCount;
        int cancelCount;

        @Override
        public void schedule(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
            scheduleCount++;
        }

        @Override
        public void cancel(Runnable task) {
            cancelCount++;
            if (this.task == task) {
                this.task = null;
            }
        }

        void fire() {
            Runnable next = task;
            task = null;
            if (next != null) {
                next.run();
            }
        }
    }
}
