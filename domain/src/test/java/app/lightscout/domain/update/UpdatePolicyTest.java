package app.lightscout.domain.update;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DCR-49: checks once a day for stable builds; the dialog shows once per version, in 09:00–20:00, when calm. */
public class UpdatePolicyTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Instant NOON = Instant.parse("2026-11-26T12:00:00Z");
    private static final AppVersion V010 = new AppVersion(0, 1, 0);
    private static final AppVersion V020 = new AppVersion(0, 2, 0);
    private static final AppVersion V030 = new AppVersion(0, 3, 0);

    private final UpdatePolicy policy = new UpdatePolicy();

    @Test
    public void checkIsDueWhenNeverCheckedOrADayPassed() {
        assertTrue(policy.isCheckDue(NOON, null, true, V010));
        assertTrue(policy.isCheckDue(NOON, NOON.minusSeconds(24 * 3600), true, V010));
        assertFalse(policy.isCheckDue(NOON, NOON.minusSeconds(24 * 3600 - 1), true, V010));
    }

    @Test
    public void checkIsDueAgainWhenTheClockWentBack() {
        assertTrue(policy.isCheckDue(NOON, NOON.plusSeconds(3600), true, V010));
    }

    @Test
    public void noCheckWhenOffOrNotAStableBuild() {
        assertFalse(policy.isCheckDue(NOON, null, false, V010));
        assertFalse(policy.isCheckDue(NOON, null, true, null));
    }

    @Test
    public void newerMeansStrictlyNewerAndKnown() {
        assertTrue(policy.isNewer(V010, V020));
        assertFalse(policy.isNewer(V020, V020));
        assertFalse(policy.isNewer(V030, V020));
        assertFalse(policy.isNewer(V010, null));
        assertFalse(policy.isNewer(null, V020));
    }

    @Test
    public void showsInsideTheWindowForANewVersionNeverOfferedBefore() {
        assertTrue(policy.shouldShow(NOON, UTC, true, true, V010, V020, null));
    }

    @Test
    public void doesNotShowTheSameVersionTwice() {
        assertFalse(policy.shouldShow(NOON, UTC, true, true, V010, V020, V020));
    }

    @Test
    public void showsAgainForAVersionNewerThanTheLastOffered() {
        assertTrue(policy.shouldShow(NOON, UTC, true, true, V010, V030, V020));
    }

    @Test
    public void doesNotShowWhenOffNotCalmOrNothingNewer() {
        assertFalse(policy.shouldShow(NOON, UTC, false, true, V010, V020, null));
        assertFalse(policy.shouldShow(NOON, UTC, true, false, V010, V020, null));
        assertFalse(policy.shouldShow(NOON, UTC, true, true, V020, V020, null));
        assertFalse(policy.shouldShow(NOON, UTC, true, true, null, V020, null));
    }

    @Test
    public void windowIsNineInclusiveToTwentyExclusiveLocalTime() {
        assertFalse(policy.shouldShow(Instant.parse("2026-11-26T08:59:59Z"), UTC, true, true, V010, V020, null));
        assertTrue(policy.shouldShow(Instant.parse("2026-11-26T09:00:00Z"), UTC, true, true, V010, V020, null));
        assertTrue(policy.shouldShow(Instant.parse("2026-11-26T19:59:59Z"), UTC, true, true, V010, V020, null));
        assertFalse(policy.shouldShow(Instant.parse("2026-11-26T20:00:00Z"), UTC, true, true, V010, V020, null));
    }

    @Test
    public void windowFollowsTheDeviceTimeZone() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");   // UTC+1 in November: 08:30 UTC is 09:30 local
        assertTrue(policy.shouldShow(Instant.parse("2026-11-26T08:30:00Z"), warsaw, true, true, V010, V020, null));
        assertFalse(policy.shouldShow(Instant.parse("2026-11-26T08:30:00Z"), UTC, true, true, V010, V020, null));
    }
}
