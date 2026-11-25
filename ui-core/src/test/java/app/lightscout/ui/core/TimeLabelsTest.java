package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

import app.lightscout.domain.statistics.PeriodKind;

/**
 * §9 "time labels (T-2)": Today / Yesterday by local date in the current zone, incl. 23 h and 25 h DST days; a
 * zone change of the {@code Clock} changes the label. Templates and patterns are the real resource values.
 */
public class TimeLabelsTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final Locale EN = Locale.ENGLISH;
    private static final String HM24 = "HH:mm";

    private static String label(Instant t, FakeClock clock) {
        return TimeLabels.label(t, clock.now(), clock.zone(), EN, HM24,
                ResXml.string("fmt_time_today"), ResXml.string("fmt_time_yesterday"),
                ResXml.string("fmt_time_date"), ResXml.string("pattern_day"));
    }

    private static Instant local(int y, int mo, int d, int h, int mi, ZoneId z) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, z).toInstant();
    }

    @Test
    public void todayYesterdayAndDateFormsMatchTheDesignExamples() {
        FakeClock clock = new FakeClock(local(2026, 10, 15, 20, 0, WARSAW), WARSAW);
        assertEquals("Today 14:32", label(local(2026, 10, 15, 14, 32, WARSAW), clock));
        assertEquals("Yesterday 21:10", label(local(2026, 10, 14, 21, 10, WARSAW), clock));
        assertEquals("Mon 12 Oct 08:05", label(local(2026, 10, 12, 8, 5, WARSAW), clock));
    }

    @Test
    public void localMidnightIsTheBoundary() {
        FakeClock clock = new FakeClock(local(2026, 10, 15, 0, 5, WARSAW), WARSAW);
        assertEquals("Today 00:00", label(local(2026, 10, 15, 0, 0, WARSAW), clock));
        assertEquals("Yesterday 23:59", label(local(2026, 10, 14, 23, 59, WARSAW), clock));
        assertEquals("Yesterday 00:00", label(local(2026, 10, 14, 0, 0, WARSAW), clock));
        assertEquals("Tue 13 Oct 23:59", label(local(2026, 10, 13, 23, 59, WARSAW), clock));
    }

    @Test
    public void twentyThreeHourDstDay() {
        // 2026-03-29 is 23 h long in Warsaw (02:00 → 03:00).
        FakeClock clock = new FakeClock(local(2026, 3, 30, 9, 0, WARSAW), WARSAW);
        Instant earliest = local(2026, 3, 29, 0, 0, WARSAW);
        Instant latest = local(2026, 3, 29, 23, 59, WARSAW);
        assertEquals(Duration.ofHours(23).plusMinutes(59).minusHours(1), Duration.between(earliest, latest));
        assertEquals("Yesterday 00:00", label(earliest, clock));
        assertEquals("Yesterday 23:59", label(latest, clock));
        assertEquals("Sat 28 Mar 23:59", label(earliest.minusSeconds(60), clock));
    }

    @Test
    public void twentyFiveHourDstDay() {
        // 2026-10-25 is 25 h long in Warsaw (03:00 → 02:00).
        FakeClock clock = new FakeClock(local(2026, 10, 25, 23, 30, WARSAW), WARSAW);
        Instant start = local(2026, 10, 25, 0, 0, WARSAW);
        assertEquals(Duration.ofHours(24).plusMinutes(30), Duration.between(start, clock.now()));
        assertEquals("Today 00:00", label(start, clock));
        assertEquals("Yesterday 23:59", label(start.minusSeconds(60), clock));
    }

    @Test
    public void zoneChangeOfTheClockChangesTheLabel() {
        Instant t = Instant.parse("2026-10-14T23:30:00Z");                 // 01:30 on 15 Oct in Warsaw
        FakeClock clock = new FakeClock(Instant.parse("2026-10-15T08:00:00Z"), WARSAW);
        assertEquals("Today 01:30", label(t, clock));
        clock.setZone(ZoneId.of("America/New_York"));                       // 19:30 on 14 Oct in New York
        assertEquals("Yesterday 19:30", label(t, clock));
    }

    @Test
    public void twelveHourPatternIsUsedWhenGiven() {
        FakeClock clock = new FakeClock(local(2026, 10, 15, 20, 0, WARSAW), WARSAW);
        String text = TimeLabels.label(local(2026, 10, 15, 14, 32, WARSAW), clock.now(), WARSAW, EN, "h:mm a",
                ResXml.string("fmt_time_today"), ResXml.string("fmt_time_yesterday"),
                ResXml.string("fmt_time_date"), ResXml.string("pattern_day"));
        assertEquals("Today 2:32 PM", text);
    }

    @Test
    public void unsupportedClockPatternFallsBackPerTwentyFourHourSetting() {
        // IR-61: ICU patterns may contain letters java.time rejects (B below API 34; "b" is rejected everywhere).
        assertEquals("h:mm a", TimeLabels.safeClockPattern("h:mm b", false, EN));
        assertEquals("H:mm", TimeLabels.safeClockPattern("H:mm b", true, EN));
        assertEquals("h:mm a", TimeLabels.safeClockPattern(null, false, EN));
        assertEquals("H:mm", TimeLabels.safeClockPattern("", true, EN));
        assertEquals("HH:mm", TimeLabels.safeClockPattern("HH:mm", true, EN));
        // JDK 21 accepts B; whatever is returned must format without exception.
        String withB = TimeLabels.safeClockPattern("B h:mm", false, Locale.TRADITIONAL_CHINESE);
        TimeLabels.format(Instant.EPOCH, WARSAW, withB, Locale.TRADITIONAL_CHINESE);
    }

    @Test
    public void periodLabels() {
        String day = ResXml.string("pattern_day");
        String dayMonth = ResXml.string("pattern_day_month");
        String range = ResXml.string("fmt_period_range");
        Instant yStart = local(2026, 10, 11, 0, 0, WARSAW);
        assertEquals("Sun 11 Oct", TimeLabels.period(PeriodKind.YESTERDAY, yStart,
                local(2026, 10, 12, 0, 0, WARSAW), WARSAW, EN, day, dayMonth, range));
        Instant now = local(2026, 10, 12, 9, 15, WARSAW);
        assertEquals("5 Oct – 12 Oct", TimeLabels.period(PeriodKind.LAST_7_DAYS, now.minus(Duration.ofDays(7)),
                now, WARSAW, EN, day, dayMonth, range));
    }
}
