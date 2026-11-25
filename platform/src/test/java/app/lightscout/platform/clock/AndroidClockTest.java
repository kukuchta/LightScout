package app.lightscout.platform.clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CP-05 §9 „Clock (JVM)”: {@code zone()} follows a changed default zone (T-2, WF-10); {@code elapsedRealtimeMs()}
 * comes from the monotonic source; {@code now()} is the current instant.
 */
public class AndroidClockTest {

    private final TimeZone original = TimeZone.getDefault();

    @After
    public void restoreZone() {
        TimeZone.setDefault(original);
    }

    @Test
    public void zoneFollowsChangedDefaultZone() {
        AndroidClock clock = new AndroidClock(() -> 0L);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"));
        assertEquals(ZoneId.of("Europe/Warsaw"), clock.zone());
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        assertEquals(ZoneId.of("America/New_York"), clock.zone());
    }

    @Test
    public void elapsedRealtimeComesFromMonotonicSourceAndNeverDecreases() {
        AtomicLong source = new AtomicLong(1_000);
        AndroidClock clock = new AndroidClock(() -> source.addAndGet(5));
        long previous = clock.elapsedRealtimeMs();
        for (int i = 0; i < 100; i++) {
            long next = clock.elapsedRealtimeMs();
            assertTrue(next >= previous);
            previous = next;
        }
        assertEquals(1_505, previous);
    }

    @Test
    public void elapsedRealtimeIsImmuneToWallClockZoneChange() {
        AtomicLong source = new AtomicLong(42);
        AndroidClock clock = new AndroidClock(source::get);
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        assertEquals(42, clock.elapsedRealtimeMs());
    }

    @Test
    public void nowIsCurrentInstant() {
        Instant before = Instant.now();
        Instant now = new AndroidClock(() -> 0L).now();
        Instant after = Instant.now();
        assertTrue(!now.isBefore(before) && !now.isAfter(after.plus(Duration.ofMillis(1))));
    }
}
