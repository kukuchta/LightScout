package app.lightscout.platform.clock;

import android.os.SystemClock;

import java.time.Instant;
import java.time.ZoneId;
import java.util.function.LongSupplier;

import app.lightscout.domain.port.Clock;

/**
 * IF-15 {@link Clock} on Android (CP-05 §8.4).
 *
 * <ul>
 *   <li>{@link #now()}: the current UTC instant (wall clock);</li>
 *   <li>{@link #elapsedRealtimeMs()}: {@code SystemClock.elapsedRealtime()}, monotonic since boot and immune to
 *       clock jumps (06 §11 F14);</li>
 *   <li>{@link #zone()}: the phone's current zone, re-read on every call and never cached (T-2, WF-10).</li>
 * </ul>
 * Thread-safe and stateless; safe before the first unlock.
 */
public final class AndroidClock implements Clock {

    private final LongSupplier elapsedRealtime;

    /**
     * Creates the clock backed by {@code SystemClock.elapsedRealtime()}.
     */
    public AndroidClock() {
        this(SystemClock::elapsedRealtime);
    }

    /**
     * Creates the clock with an explicit monotonic source.
     *
     * @param elapsedRealtime monotonic milliseconds since boot
     */
    AndroidClock(LongSupplier elapsedRealtime) {
        this.elapsedRealtime = elapsedRealtime;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public long elapsedRealtimeMs() {
        return elapsedRealtime.getAsLong();
    }

    @Override
    public ZoneId zone() {
        // The framework updates the default TimeZone on ACTION_TIMEZONE_CHANGED; read it on every call.
        return ZoneId.systemDefault();
    }
}
