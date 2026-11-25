package app.lightscout.ui.core;

import java.time.Instant;
import java.time.ZoneId;

import app.lightscout.domain.port.Clock;

/**
 * Settable IF-15 clock for tests.
 */
final class FakeClock implements Clock {

    private Instant now;
    private ZoneId zone;

    FakeClock(Instant now) {
        this(now, ZoneId.of("Europe/Warsaw"));
    }

    FakeClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    void set(Instant now) {
        this.now = now;
    }

    void setZone(ZoneId zone) {
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
}
