package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class StatusNotificationContentTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant CHECKED = NOW.minusSeconds(60);

    @Test
    public void mapsEveryOutcomeAndDataLossText() {
        assertText(snapshot(FetchOutcome.OK, null, dataLoss(DataLossKind.NONE, null), true), "3 min ago");
        assertText(snapshot(FetchOutcome.OK, null,
                dataLoss(DataLossKind.DATA_LOSS, NOW.minus(Duration.ofMinutes(23))), true), "No new readings for 23 min");
        assertText(snapshot(FetchOutcome.NO_INTERNET, null, dataLoss(DataLossKind.NONE, null), true),
                "No internet connection · last reading 3 min ago");
        assertText(snapshot(FetchOutcome.NS_NOT_RESPONDING, null, dataLoss(DataLossKind.NONE, null), true),
                "Nightscout is not responding");
        assertText(snapshot(FetchOutcome.NOT_NIGHTSCOUT, null, dataLoss(DataLossKind.NONE, null), true),
                "Nightscout is not responding");
        assertText(snapshot(FetchOutcome.SERVER_TOO_OLD, null, dataLoss(DataLossKind.NONE, null), true),
                "Nightscout is not responding");
        assertText(snapshot(FetchOutcome.TOKEN_REJECTED, null, dataLoss(DataLossKind.NONE, null), true),
                "Nightscout rejected the access token");
        assertText(snapshot(FetchOutcome.PERMISSION_MISSING, FetchOutcome.PERMISSION_MISSING,
                dataLoss(DataLossKind.NONE, null), true), "Nightscout rejected the access token");
        assertText(snapshot(FetchOutcome.OK, FetchOutcome.PERMISSION_MISSING,
                dataLoss(DataLossKind.NONE, null), true), "Nightscout rejected the access token");
        assertText(snapshot(FetchOutcome.OK, FetchOutcome.SERVER_TOO_OLD,
                dataLoss(DataLossKind.NONE, null), true), "Nightscout is not responding");
        assertText(snapshot(FetchOutcome.STORAGE_ERROR, null, dataLoss(DataLossKind.NONE, null), true),
                "3 min ago");
        assertText(snapshot(FetchOutcome.OK, null, dataLoss(DataLossKind.FIRST_USE, null), true),
                "Waiting for the first reading");
    }

    @Test
    public void offlineWithoutReadingUsesFirstUseCopyAndNoReadingTitle() {
        StatusSnapshot s = snapshot(FetchOutcome.NO_INTERNET, null,
                dataLoss(DataLossKind.FIRST_USE, null), true, null);
        StatusNotificationContent.Content content = create(s);
        assertEquals("No readings", content.title());
        assertEquals("Waiting for the first reading", content.text());
        assertNull(content.iconSgv());
    }

    @Test
    public void dataLossWithoutAnyReadingUsesItsAnchorAndNotFirstUseCopy() {
        StatusSnapshot s = snapshot(FetchOutcome.OK, null,
                dataLoss(DataLossKind.DATA_LOSS, NOW.minus(Duration.ofMinutes(24))), true, null);
        assertEquals("No new readings for 24 min", create(s).text());
    }

    @Test
    public void addsAlarmsOffSuffixToEveryTextVariant() {
        for (FetchOutcome outcome : FetchOutcome.values()) {
            StatusSnapshot s = snapshot(outcome, outcome == FetchOutcome.PERMISSION_MISSING ? outcome : null,
                    dataLoss(DataLossKind.NONE, null), false);
            assertTrue("missing suffix for " + outcome, create(s).text().endsWith(" · Alarms off"));
        }
    }

    @Test
    public void staleIconUsesNullForNoReadingAndAtOrBeyondThreshold() {
        assertTrue(StatusNotificationContent.isStale(snapshot(FetchOutcome.OK, null,
                dataLoss(DataLossKind.NONE, null), true, null), NOW));
        assertTrue(StatusNotificationContent.isStale(snapshotAtAge(Duration.ofMinutes(20)), NOW));
        assertFalse(StatusNotificationContent.isStale(snapshotAtAge(Duration.ofMinutes(19).plusSeconds(59)), NOW));
        assertEquals(Integer.valueOf(142), create(snapshotAtAge(Duration.ofMinutes(19).plusSeconds(59))).iconSgv());
        assertNull(create(snapshotAtAge(Duration.ofMinutes(20))).iconSgv());
    }

    private static void assertText(StatusSnapshot s, String expected) {
        assertEquals(expected, create(s).text());
    }

    private static StatusNotificationContent.Content create(StatusSnapshot snapshot) {
        StatusNotificationContent.Formatter formatter = new StatusNotificationContent.Formatter() {
            @Override public String age(Duration age) { return "3 min ago"; }
            @Override public String arrow(Trend trend) { return trend == Trend.SINGLE_UP ? "↑" : ""; }
            @Override public String spokenTrend(Trend trend) { return trend == Trend.SINGLE_UP ? "rising" : ""; }
        };
        return StatusNotificationContent.create(snapshot, NOW, ZoneOffset.UTC, new TestStrings(), formatter);
    }

    @Test
    public void contentCarriesTheSnapshotDelta() {
        StatusSnapshot base = snapshot(FetchOutcome.OK, null, dataLoss(DataLossKind.NONE, null), true);
        StatusSnapshot withDelta = new StatusSnapshot(base.latest(), base.readingAge(), base.nextExpected(),
                base.overdue(), base.dataLoss(), base.lastOutcome(), base.connectionProblem(),
                base.lastSuccessfulCheck(), base.lastCheckCompleted(), base.online(), base.fetching(),
                base.configured(), base.alarmsEnabled(), base.lowEnabled(), base.highEnabled(),
                base.urgentLowMgdl(), base.lowMgdl(), base.highMgdl(), base.noDataMinutes(), base.ringing(),
                base.iobCenti(), base.siteStatus(), base.sensorStatus(), base.readinessMissingRequired(),
                base.bannerTop(), base.computedAt(), base.snoozes(), 9);
        assertEquals(Integer.valueOf(9), create(withDelta).deltaMgdl());
        assertNull(create(base).deltaMgdl());
        assertNull(create(null).deltaMgdl());
    }

    @Test
    public void staleReadingShowsNoDelta() {
        StatusSnapshot stale = snapshotAtAge(Duration.ofMinutes(20));
        StatusSnapshot withDelta = new StatusSnapshot(stale.latest(), stale.readingAge(), stale.nextExpected(),
                stale.overdue(), stale.dataLoss(), stale.lastOutcome(), stale.connectionProblem(),
                stale.lastSuccessfulCheck(), stale.lastCheckCompleted(), stale.online(), stale.fetching(),
                stale.configured(), stale.alarmsEnabled(), stale.lowEnabled(), stale.highEnabled(),
                stale.urgentLowMgdl(), stale.lowMgdl(), stale.highMgdl(), stale.noDataMinutes(), stale.ringing(),
                stale.iobCenti(), stale.siteStatus(), stale.sensorStatus(), stale.readinessMissingRequired(),
                stale.bannerTop(), stale.computedAt(), stale.snoozes(), 9);
        assertNull(create(withDelta).iconSgv());
        assertNull(create(withDelta).deltaMgdl());
    }

    @Test
    public void titleReadsReadingThenDeltaThenArrow() {
        StatusSnapshot withDelta = withDelta(snapshotAtAge(Duration.ofMinutes(3)), 9);
        assertEquals("142 +9 mg/dL ↑", create(withDelta).title());
        assertEquals("142 -4 mg/dL ↑", create(withDelta(snapshotAtAge(Duration.ofMinutes(3)), -4)).title());
        assertEquals("142 +0 mg/dL ↑", create(withDelta(snapshotAtAge(Duration.ofMinutes(3)), 0)).title());
    }

    @Test
    public void titleWithoutDeltaOrArrowHasNoStraySpaces() {
        assertEquals("142 mg/dL ↑", create(snapshotAtAge(Duration.ofMinutes(3))).title());
        StatusSnapshot noTrend = new StatusSnapshot(new Reading("r", NOW.minusSeconds(60), 142, Trend.FLAT, "u"),
                Duration.ofSeconds(60), null, false, dataLoss(DataLossKind.NONE, null), FetchOutcome.OK, null,
                CHECKED, CHECKED, true, false, true, true, true, true, 55, 70, 250, 20, null, null,
                consumable(ConsumableKind.SITE), consumable(ConsumableKind.SENSOR), 0, Banner.NONE, NOW);
        assertEquals("142 mg/dL", create(noTrend).title());
    }

    @Test
    public void staleReadingTitleHasNoDelta() {
        assertEquals("142 mg/dL ↑", create(withDelta(snapshotAtAge(Duration.ofMinutes(20)), 9)).title());
    }

    private static StatusSnapshot withDelta(StatusSnapshot b, int delta) {
        return new StatusSnapshot(b.latest(), b.readingAge(), b.nextExpected(),
                b.overdue(), b.dataLoss(), b.lastOutcome(), b.connectionProblem(),
                b.lastSuccessfulCheck(), b.lastCheckCompleted(), b.online(), b.fetching(),
                b.configured(), b.alarmsEnabled(), b.lowEnabled(), b.highEnabled(),
                b.urgentLowMgdl(), b.lowMgdl(), b.highMgdl(), b.noDataMinutes(), b.ringing(),
                b.iobCenti(), b.siteStatus(), b.sensorStatus(), b.readinessMissingRequired(),
                b.bannerTop(), b.computedAt(), b.snoozes(), delta);
    }

    private static StatusSnapshot snapshotAtAge(Duration age) {
        return snapshot(FetchOutcome.OK, null, dataLoss(DataLossKind.NONE, null), true,
                reading(NOW.minus(age)));
    }

    private static StatusSnapshot snapshot(FetchOutcome outcome, FetchOutcome connectionProblem,
                                           DataLossStatus dataLoss, boolean alarmsEnabled) {
        return snapshot(outcome, connectionProblem, dataLoss, alarmsEnabled, reading(NOW.minusSeconds(180)));
    }

    private static StatusSnapshot snapshot(FetchOutcome outcome, FetchOutcome connectionProblem,
                                           DataLossStatus dataLoss, boolean alarmsEnabled, Reading reading) {
        return new StatusSnapshot(reading, reading == null ? null : Duration.between(reading.time(), NOW),
                null, false, dataLoss, outcome, connectionProblem, CHECKED, CHECKED, true, false,
                true, alarmsEnabled, true, true, 55, 70, 250, 20, null, null,
                consumable(ConsumableKind.SITE), consumable(ConsumableKind.SENSOR), 0, Banner.NONE, NOW);
    }

    private static Reading reading(Instant time) {
        return new Reading("reading-1", time, 142, Trend.SINGLE_UP, "uploader");
    }

    private static DataLossStatus dataLoss(DataLossKind kind, Instant since) {
        return new DataLossStatus(kind, kind == DataLossKind.DATA_LOSS ? DataLossCause.UPLOADER : null, since);
    }

    private static ConsumableStatus consumable(ConsumableKind kind) {
        return new ConsumableStatus(kind, ConsumableState.NONE_FOUND, null, null, null, null);
    }

    private static final class TestStrings implements StatusNotificationContent.Strings {
        @Override public String titleEmpty() { return "No readings"; }
        @Override public String reading(int sgv, String signedDelta) {
            return signedDelta == null ? sgv + " mg/dL" : sgv + " " + signedDelta + " mg/dL";
        }
        @Override public String firstUse() { return "Waiting for the first reading"; }
        @Override public String dataLoss(long minutes) { return "No new readings for " + minutes + " min"; }
        @Override public String offline(long minutes) {
            return "No internet connection · last reading " + minutes + " min ago";
        }
        @Override public String errorNotResponding() { return "Nightscout is not responding"; }
        @Override public String errorTokenRejected() { return "Nightscout rejected the access token"; }
        @Override public String normal(String age) { return age; }
        @Override public String alarmsOffSuffix() { return " · Alarms off"; }
    }
}
