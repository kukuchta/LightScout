package app.lightscout.widget;

/**
 * Thread-safe one-render-per-second limiter for IF-21 and launcher-triggered updates.
 */
public final class WidgetRenderThrottle {

    private static final long MIN_INTERVAL_MS = 1_000L;
    private long lastRenderMs = Long.MIN_VALUE;

    /**
     * Reserves a render slot if at least one second has passed.
     *
     * @param elapsedRealtimeMs monotonic time from IF-15
     * @return whether the caller may render
     */
    public synchronized boolean tryAcquire(long elapsedRealtimeMs) {
        if (lastRenderMs == Long.MIN_VALUE || elapsedRealtimeMs - lastRenderMs >= MIN_INTERVAL_MS) {
            lastRenderMs = elapsedRealtimeMs;
            return true;
        }
        return false;
    }

    /**
     * Returns the delay until the next render slot.
     *
     * @param elapsedRealtimeMs monotonic time from IF-15
     * @return zero when a render may run now
     */
    public synchronized long millisUntilNext(long elapsedRealtimeMs) {
        if (lastRenderMs == Long.MIN_VALUE) {
            return 0;
        }
        return Math.max(0L, MIN_INTERVAL_MS - (elapsedRealtimeMs - lastRenderMs));
    }
}
