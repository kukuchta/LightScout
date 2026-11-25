package app.lightscout.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;

public class WidgetStateMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    public void firstUseTakesPriorityAndNoReadingMapsToEmpty() {
        WidgetPresentation firstUse = map(false, reading(142, NOW.minusSeconds(60)), 20,
                true, FetchOutcome.OK, NOW);
        assertEquals(WidgetPresentation.State.FIRST_USE, firstUse.state());
        assertNull(firstUse.latest());

        WidgetPresentation empty = map(true, null, 20, true, FetchOutcome.OK, NOW);
        assertEquals(WidgetPresentation.State.NO_READINGS, empty.state());
        assertNull(empty.latest());
        assertEquals(WidgetPresentation.RangeLabel.NONE, empty.rangeLabel());
    }

    @Test
    public void rangeLabelsUseInclusiveThresholds() {
        assertEquals(WidgetPresentation.RangeLabel.URGENT_LOW,
                map(true, reading(55, NOW), 20, true, FetchOutcome.OK, NOW).rangeLabel());
        assertEquals(WidgetPresentation.RangeLabel.LOW,
                map(true, reading(70, NOW), 20, true, FetchOutcome.OK, NOW).rangeLabel());
        assertEquals(WidgetPresentation.RangeLabel.NONE,
                map(true, reading(71, NOW), 20, true, FetchOutcome.OK, NOW).rangeLabel());
        assertEquals(WidgetPresentation.RangeLabel.NONE,
                map(true, reading(249, NOW), 20, true, FetchOutcome.OK, NOW).rangeLabel());
        assertEquals(WidgetPresentation.RangeLabel.HIGH,
                map(true, reading(250, NOW), 20, true, FetchOutcome.OK, NOW).rangeLabel());
    }

    @Test
    public void staleAgeAndNoInternetUseErrorStateAndAgeIsClamped() {
        WidgetPresentation fresh = map(true, reading(142, NOW.minusSeconds(19 * 60L + 59)), 20,
                true, FetchOutcome.OK, NOW);
        assertFalse(fresh.stale());
        assertEquals(Duration.ofSeconds(19 * 60L + 59), fresh.age());

        WidgetPresentation stale = map(true, reading(142, NOW.minusSeconds(20 * 60L)), 20,
                true, FetchOutcome.OK, NOW);
        assertTrue(stale.stale());
        assertNotNull(stale.age());

        WidgetPresentation offline = map(true, reading(142, NOW.minusSeconds(60)), 20,
                false, FetchOutcome.NO_INTERNET, NOW);
        assertTrue(offline.stale());

        WidgetPresentation future = map(true, reading(142, NOW.plusSeconds(60)), 20,
                true, FetchOutcome.OK, NOW);
        assertEquals(Duration.ZERO, future.age());
    }

    @Test
    public void ageUsesTheCurrentIf15InstantOnEachStatusRefresh() {
        StatusSnapshot snapshot = snapshot(true, reading(142, NOW.minusSeconds(3 * 60L)), 20,
                true, FetchOutcome.OK);
        WidgetPresentation firstMinute = WidgetStateMapper.map(snapshot, NOW);
        WidgetPresentation nextMinute = WidgetStateMapper.map(snapshot, NOW.plusSeconds(60));

        assertEquals(Duration.ofMinutes(3), firstMinute.age());
        assertEquals(Duration.ofMinutes(4), nextMinute.age());
    }

    @Test
    public void deltaIsKeptWhileTheReadingIsFreshAndDroppedFromTheNoDataThreshold() {
        Instant justFresh = NOW.minusSeconds(19 * 60L + 59);
        assertEquals(Integer.valueOf(9), WidgetStateMapper.map(withDelta(snapshot(true, reading(142, justFresh), 20,
                true, FetchOutcome.OK), 9), NOW).deltaMgdl());
        Instant atThreshold = NOW.minusSeconds(20 * 60L);
        assertNull(WidgetStateMapper.map(withDelta(snapshot(true, reading(142, atThreshold), 20,
                true, FetchOutcome.OK), 9), NOW).deltaMgdl());
        assertNull(WidgetStateMapper.map(withDelta(snapshot(true, reading(142, NOW.minusSeconds(60)), 20,
                true, FetchOutcome.OK), null), NOW).deltaMgdl());
        assertNull(WidgetStateMapper.map(withDelta(snapshot(false, reading(142, NOW), 20,
                true, FetchOutcome.OK), 9), NOW).deltaMgdl());
    }

    private static StatusSnapshot withDelta(StatusSnapshot b, Integer delta) {
        return new StatusSnapshot(b.latest(), b.readingAge(), b.nextExpected(), b.overdue(), b.dataLoss(),
                b.lastOutcome(), b.connectionProblem(), b.lastSuccessfulCheck(), b.lastCheckCompleted(), b.online(),
                b.fetching(), b.configured(), b.alarmsEnabled(), b.lowEnabled(), b.highEnabled(), b.urgentLowMgdl(),
                b.lowMgdl(), b.highMgdl(), b.noDataMinutes(), b.ringing(), b.iobCenti(), b.siteStatus(),
                b.sensorStatus(), b.readinessMissingRequired(), b.bannerTop(), b.computedAt(), b.snoozes(), delta);
    }

    private static WidgetPresentation map(boolean configured, Reading reading, int noDataMinutes,
                                          boolean online, FetchOutcome outcome, Instant now) {
        return WidgetStateMapper.map(snapshot(configured, reading, noDataMinutes, online, outcome), now);
    }

    private static StatusSnapshot snapshot(boolean configured, Reading reading, int noDataMinutes,
                                           boolean online, FetchOutcome outcome) {
        Duration age = reading == null ? null : Duration.between(reading.time(), NOW);
        return new StatusSnapshot(reading, age, null, false,
                new DataLossStatus(DataLossKind.NONE, null, null), outcome, null, null, null,
                online, false, configured,
                true, true, true, 55, 70, 250, noDataMinutes,
                (AlarmType) null, null,
                new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND, null, null, null, null),
                new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND, null, null, null, null),
                0, Banner.NONE, NOW);
    }

    private static Reading reading(int sgv, Instant time) {
        return new Reading("synthetic", time, sgv, Trend.SINGLE_UP, "test");
    }
}
