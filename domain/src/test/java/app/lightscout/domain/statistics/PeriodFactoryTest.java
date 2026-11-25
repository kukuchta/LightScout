package app.lightscout.domain.statistics;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * TST-01 STAT-4, STAT-7 and T-1 / T-2 (period boundaries follow the zone passed in) (CP-01 §8.5, §8.8, §9).
 */
public class PeriodFactoryTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    public void stat4_dstStart_yesterdayIs23h_276Expected() {
        Period p = PeriodFactory.yesterday(ZonedDateTime.of(2026, 3, 30, 10, 0, 0, 0, WARSAW));
        assertEquals(PeriodKind.YESTERDAY, p.kind());
        assertEquals(ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, WARSAW).toInstant(), p.start());
        assertEquals(ZonedDateTime.of(2026, 3, 30, 0, 0, 0, 0, WARSAW).toInstant(), p.end());
        assertEquals(Duration.ofHours(23), Duration.between(p.start(), p.end()));
        assertEquals(276, p.expectedReadings());
    }

    @Test
    public void stat4_dstEnd_yesterdayIs25h_300Expected() {
        Period p = PeriodFactory.yesterday(ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, WARSAW));
        assertEquals(ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, WARSAW).toInstant(), p.start());
        assertEquals(Duration.ofHours(25), Duration.between(p.start(), p.end()));
        assertEquals(300, p.expectedReadings());
    }

    @Test
    public void normalDay_yesterdayIs24h_288Expected() {
        Period p = PeriodFactory.yesterday(ZonedDateTime.of(2026, 10, 15, 0, 30, 0, 0, WARSAW));
        assertEquals(Instant.parse("2026-10-13T22:00:00Z"), p.start());
        assertEquals(288, p.expectedReadings());
    }

    @Test
    public void stat7_lastDays_endsAtNow() {
        Instant now = Instant.parse("2026-10-15T12:34:56Z");
        Period p7 = PeriodFactory.lastDays(7, now);
        assertEquals(PeriodKind.LAST_7_DAYS, p7.kind());
        assertEquals(now, p7.end());
        assertEquals(now.minus(Duration.ofDays(7)), p7.start());
        assertEquals(2016, p7.expectedReadings());

        assertEquals(PeriodKind.LAST_24_HOURS, PeriodFactory.lastDays(1, now).kind());
        assertEquals(PeriodKind.LAST_14_DAYS, PeriodFactory.lastDays(14, now).kind());
        Period p30 = PeriodFactory.lastDays(30, now);
        assertEquals(PeriodKind.LAST_30_DAYS, p30.kind());
        assertEquals(now.minus(Duration.ofDays(30)), p30.start());
    }

    @Test(expected = IllegalArgumentException.class)
    public void lastDays_otherDays_rejected() {
        PeriodFactory.lastDays(5, Instant.parse("2026-10-15T12:00:00Z"));
    }

    @Test
    public void t2_t4_boundariesFollowZonePassedIn() {
        Instant instant = Instant.parse("2026-10-15T12:00:00Z");
        Period warsaw = PeriodFactory.yesterday(instant.atZone(WARSAW));
        Period newYork = PeriodFactory.yesterday(instant.atZone(ZoneId.of("America/New_York")));
        assertNotEquals(warsaw.start(), newYork.start());
        assertEquals(Instant.parse("2026-10-13T22:00:00Z"), warsaw.start());
        assertEquals(Instant.parse("2026-10-14T04:00:00Z"), newYork.start());
    }
}
