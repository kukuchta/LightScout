package app.lightscout.ui.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * §9 "readingAge" (boundaries G-05 → CP-00A R-82) and "consumable age (T-3)". The age is computed by the caller
 * as {@code clock.now() − reading.time()}; {@link FakeClock} plays IF-15.
 */
public class AgeTextTest {

    private static final Instant READING = Instant.parse("2026-10-15T12:00:00Z");

    private static String visible(Duration age) {
        if (age == null) {
            return ResXml.string("s01_age_no_readings");
        }
        AgeText.Parts p = AgeText.reading(age);
        switch (p.kind()) {
            case JUST_NOW:
                return ResXml.string("s01_age_just_now");
            case MINUTES:
                return String.format(Locale.ENGLISH, ResXml.string("s01_age_min"), p.minutes());
            default:
                return String.format(Locale.ENGLISH, ResXml.string("s01_age_h_min"), p.hours(), p.minutes());
        }
    }

    private static Duration ageAt(Duration sinceReading) {
        FakeClock clock = new FakeClock(READING.plus(sinceReading));
        return Duration.between(READING, clock.now());
    }

    @Test
    public void noReadingGivesNoReadings() {
        assertEquals("No readings", visible(null));
    }

    @Test
    public void justNowBelowSixtySeconds() {
        assertEquals("Just now", visible(ageAt(Duration.ZERO)));
        assertEquals("Just now", visible(ageAt(Duration.ofSeconds(59))));
        assertEquals("Just now", visible(Duration.ofSeconds(-30)));   // future-dated within M-9 tolerance → 0
    }

    @Test
    public void minutesFromOneToFiftyNineFloor() {
        assertEquals("1 min ago", visible(ageAt(Duration.ofSeconds(60))));
        assertEquals("1 min ago", visible(ageAt(Duration.ofSeconds(119))));
        assertEquals("3 min ago", visible(ageAt(Duration.ofMinutes(3).plusSeconds(59))));
        assertEquals("59 min ago", visible(ageAt(Duration.ofMinutes(59).plusSeconds(59))));
    }

    @Test
    public void hoursAndMinutesFromSixtyMinutesFloor() {
        assertEquals("1 h 0 min ago", visible(ageAt(Duration.ofMinutes(60))));
        assertEquals("1 h 5 min ago", visible(ageAt(Duration.ofMinutes(65).plusSeconds(59))));
        assertEquals("26 h 3 min ago", visible(ageAt(Duration.ofHours(26).plusMinutes(3))));
    }

    @Test
    public void widgetAgeUsesTotalMinutes() {
        assertEquals(3, AgeText.totalMinutes(Duration.ofMinutes(3).plusSeconds(40)));
        assertEquals(75, AgeText.totalMinutes(Duration.ofMinutes(75)));
        assertEquals(0, AgeText.totalMinutes(Duration.ofMinutes(-2)));
    }

    @Test
    public void consumableFiftyThreeHoursIsTwoDaysFiveHours() {
        assertArrayEquals(new long[] {2, 5}, AgeText.daysHours(Duration.ofHours(53)));
        assertEquals("2 d 5 h", String.format(Locale.ENGLISH, ResXml.string("s01_counter_value"), 2L, 5L));
        assertArrayEquals(new long[] {2, 5}, AgeText.daysHours(Duration.ofHours(53).plusMinutes(59)));
        assertArrayEquals(new long[] {0, 0}, AgeText.daysHours(Duration.ofHours(-1)));
    }

    @Test
    public void consumableAgeAcrossDstCountsAbsoluteHours() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        // 2026-10-25 03:00 CEST → 02:00 CET (25 h day). Wall clock says 2 d 0 h, absolute time is 2 d 1 h.
        Instant change = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, warsaw).toInstant();
        Instant now = ZonedDateTime.of(2026, 10, 26, 12, 0, 0, 0, warsaw).toInstant();
        assertArrayEquals(new long[] {2, 1}, AgeText.daysHours(Duration.between(change, now)));
        // 2026-03-29 02:00 CET → 03:00 CEST (23 h day). Wall clock 2 d 0 h, absolute 1 d 23 h.
        Instant springChange = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, warsaw).toInstant();
        Instant springNow = ZonedDateTime.of(2026, 3, 30, 12, 0, 0, 0, warsaw).toInstant();
        assertArrayEquals(new long[] {1, 23}, AgeText.daysHours(Duration.between(springChange, springNow)));
    }
}
