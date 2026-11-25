package app.lightscout.domain;

import app.lightscout.domain.port.Clock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Hand-written fake of IF-15 {@link Clock} for tests (no Mockito, CP-00 §2). Rules never read it; tests use it to
 * produce the {@code now} they pass in.
 */
public final class FakeClock implements Clock {

    private Instant now;
    private final ZoneId zone;

    /**
     * Creates a clock fixed at {@code start} in UTC.
     *
     * @param start initial instant
     */
    public FakeClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    /**
     * Creates a clock fixed at {@code start} in a zone.
     *
     * @param start initial instant
     * @param zone  zone
     */
    public FakeClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    @Override
    public Instant now() {
        return now;
    }

    @Override
    public long elapsedRealtimeMs() {
        return now.toEpochMilli();
    }

    @Override
    public ZoneId zone() {
        return zone;
    }

    /**
     * Moves the clock forward.
     *
     * @param d amount
     */
    public void advance(Duration d) {
        now = now.plus(d);
    }

    /**
     * Sets the clock.
     *
     * @param t new instant
     */
    public void set(Instant t) {
        now = t;
    }
}
