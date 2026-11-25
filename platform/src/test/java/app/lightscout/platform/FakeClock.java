package app.lightscout.platform;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import app.lightscout.domain.port.Clock;

/**
 * Settable IF-15 clock for tests.
 */
public final class FakeClock implements Clock {

    private Instant now;
    private long elapsed;

    /**
     * Creates the clock.
     *
     * @param start initial instant
     */
    public FakeClock(Instant start) {
        this.now = start;
    }

    /**
     * Advances both the wall clock and the elapsed time.
     *
     * @param d amount
     */
    public void advance(Duration d) {
        now = now.plus(d);
        elapsed += d.toMillis();
    }

    @Override
    public Instant now() {
        return now;
    }

    @Override
    public long elapsedRealtimeMs() {
        return elapsed;
    }

    @Override
    public ZoneId zone() {
        return ZoneOffset.UTC;
    }
}
