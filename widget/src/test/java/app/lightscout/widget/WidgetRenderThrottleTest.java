package app.lightscout.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WidgetRenderThrottleTest {

    @Test
    public void burstWithinOneSecondRendersOnce() {
        WidgetRenderThrottle throttle = new WidgetRenderThrottle();
        int renders = 0;
        for (int i = 0; i < 10; i++) {
            if (throttle.tryAcquire(10_000L + i * 90L)) {
                renders++;
            }
        }
        assertEquals(1, renders);
        assertFalse(throttle.tryAcquire(10_999L));
        assertTrue(throttle.tryAcquire(11_000L));
    }

    @Test
    public void exposesDelayUntilNextSlot() {
        WidgetRenderThrottle throttle = new WidgetRenderThrottle();
        assertEquals(0L, throttle.millisUntilNext(50L));
        assertTrue(throttle.tryAcquire(50L));
        assertEquals(650L, throttle.millisUntilNext(400L));
        assertTrue(throttle.tryAcquire(1_050L));
    }

    @Test
    public void coalescedUpdateKeepsNewestSnapshotAndRequestedWidgets() {
        WidgetRenderQueue<String> queue = new WidgetRenderQueue<>();
        queue.offer("old", new int[]{1});
        queue.offer("new", new int[]{2, 1});

        WidgetRenderQueue.Request<String> request = queue.poll();

        assertEquals("new", request.snapshot());
        assertArrayEquals(new int[]{1, 2}, request.widgetIds());
        assertNull(queue.poll());
    }

    @Test
    public void allWidgetUpdateRemainsAllWidgetUpdateWhenCoalesced() {
        WidgetRenderQueue<String> queue = new WidgetRenderQueue<>();
        queue.offer("old", new int[]{1});
        queue.offer("new", null);

        WidgetRenderQueue.Request<String> request = queue.poll();

        assertEquals("new", request.snapshot());
        assertNull(request.widgetIds());
    }

    @Test
    public void newerImmediateRenderUpdatesPendingSnapshotWithoutDroppingIds() {
        WidgetRenderQueue<String> queue = new WidgetRenderQueue<>();
        queue.offer("queued", new int[]{4});

        queue.replaceSnapshotIfPending("immediate");

        WidgetRenderQueue.Request<String> request = queue.poll();
        assertEquals("immediate", request.snapshot());
        assertArrayEquals(new int[]{4}, request.widgetIds());
    }
}
