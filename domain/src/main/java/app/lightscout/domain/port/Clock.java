package app.lightscout.domain.port;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Time source (IF-15), implemented by {@code :platform}. Rules in {@code :domain} never read it:
 * callers pass {@code now} / {@code ZoneId} in.
 */
public interface Clock {

    /**
     * Returns the current instant.
     *
     * @return now (UTC)
     */
    Instant now();

    /**
     * Returns monotonic time since boot.
     *
     * @return elapsed realtime in ms
     */
    long elapsedRealtimeMs();

    /**
     * Returns the phone's current time zone (never cached — T-4).
     *
     * @return zone
     */
    ZoneId zone();
}
