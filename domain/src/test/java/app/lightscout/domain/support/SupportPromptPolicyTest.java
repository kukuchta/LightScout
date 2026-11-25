package app.lightscout.domain.support;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DCR-46 (D-166): the one-time support prompt is shown only after 7 days, in 09:00–20:00 local time, when calm. */
public class SupportPromptPolicyTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Instant FIRST = Instant.parse("2026-11-10T08:00:00Z");
    private static final Instant DAY7_NOON = Instant.parse("2026-11-17T12:00:00Z");

    private final SupportPromptPolicy policy = new SupportPromptPolicy();

    private boolean show(Instant now, ZoneId zone, Instant firstUse, boolean shown, boolean calm) {
        return policy.shouldShow(now, zone, firstUse, shown, calm);
    }

    @Test
    public void showsAfterSevenDaysInsideTheWindowWhenCalmAndNotShownYet() {
        assertTrue(show(DAY7_NOON, UTC, FIRST, false, true));
    }

    @Test
    public void neverBeforeSevenDays() {
        assertFalse(show(Instant.parse("2026-11-16T12:00:00Z"), UTC, FIRST, false, true));
    }

    @Test
    public void exactlySevenDaysAfterFirstUseIsEnough() {
        Instant first = Instant.parse("2026-11-10T12:00:00Z");
        assertTrue(show(first.plus(Duration.ofDays(7)), UTC, first, false, true));
        assertFalse(show(first.plus(Duration.ofDays(7)).minusSeconds(1), UTC, first, false, true));
    }

    @Test
    public void neverTwice() {
        assertFalse(show(DAY7_NOON, UTC, FIRST, true, true));
    }

    @Test
    public void neverWhenNotCalm() {
        assertFalse(show(DAY7_NOON, UTC, FIRST, false, false));
    }

    @Test
    public void windowIsNineInclusiveToTwentyExclusiveInLocalTime() {
        assertFalse(show(Instant.parse("2026-11-17T08:59:59Z"), UTC, FIRST, false, true));
        assertTrue(show(Instant.parse("2026-11-17T09:00:00Z"), UTC, FIRST, false, true));
        assertTrue(show(Instant.parse("2026-11-17T19:59:59Z"), UTC, FIRST, false, true));
        assertFalse(show(Instant.parse("2026-11-17T20:00:00Z"), UTC, FIRST, false, true));
        assertFalse(show(Instant.parse("2026-11-17T03:00:00Z"), UTC, FIRST, false, true));
    }

    @Test
    public void windowFollowsTheDeviceZoneNotUtc() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");   // UTC+1 in November
        assertTrue("10:00 local", show(Instant.parse("2026-11-17T09:00:00Z"), warsaw, FIRST, false, true));
        assertFalse("08:30 local", show(Instant.parse("2026-11-17T07:30:00Z"), warsaw, FIRST, false, true));
        assertFalse("21:00 local", show(Instant.parse("2026-11-17T20:00:00Z"), warsaw, FIRST, false, true));
    }

    @Test
    public void unknownFirstUseNeverShows() {
        assertFalse(show(DAY7_NOON, UTC, null, false, true));
    }
}
