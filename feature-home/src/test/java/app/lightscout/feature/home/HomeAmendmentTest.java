package app.lightscout.feature.home;

import static org.junit.Assert.*;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.feature.home.Fakes.*;
import app.lightscout.feature.home.HomeUiState.BannerKind;
import org.junit.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** R-93/R-95 display/action boundaries, with synthetic snapshots and no runtime requests. */
public class HomeAmendmentTest {
    private final FakeClock clock = new FakeClock();
    private final FakeStatusRepository status = new FakeStatusRepository(new Snap().build());
    private final FakeAlarmActions alarms = new FakeAlarmActions();
    private final HomeModel model = new HomeModel(status, alarms, clock, new FakeTimer());

    private HomeUiState show(Snap snap) {
        model.onSnapshot(snap.build());
        return model.state();
    }

    @Test public void firstCheckSweepOnlyWithoutAReadingAndNormalFillOnceACachedReadingIsShown() {
        Snap snap = new Snap().noReading().firstUse();
        snap.lastCheckCompleted = null;
        assertEquals(FreshnessPresentation.Phase.LOADING, FreshnessPresentation.from(show(snap)).phase());
        snap.latest = Snap.reading(142, Duration.ofMinutes(3), Trend.FLAT);
        assertTrue(show(snap).loading());
        assertEquals(Integer.valueOf(142), model.state().sgv());
        // D-148: a cached reading 3 min old: the usual fill (60 %), no back-and-forth sweep
        assertEquals(FreshnessPresentation.Phase.FRESH, FreshnessPresentation.from(model.state()).phase());
        assertEquals(60, FreshnessPresentation.from(model.state()).percent());
        snap.lastCheckCompleted = clock.now().minusMillis(1);
        assertEquals(FreshnessPresentation.Phase.FRESH, FreshnessPresentation.from(show(snap)).phase());
        assertTrue(model.state().loading());
        snap.lastCheckCompleted = clock.now();
        assertEquals(FreshnessPresentation.Phase.FRESH, FreshnessPresentation.from(show(snap)).phase());
        snap.latest = null;
        assertEquals(FreshnessPresentation.Phase.EMPTY, FreshnessPresentation.from(show(snap)).phase());
        assertFalse(model.state().loading());
        assertEquals(0, status.retries);
    }

    @Test public void withoutACycleAnOverdueReadingIsAFullOrangeTrackAndNeverChangesAlarmsOrRetries() {
        Snap snap = new Snap().latest(Snap.reading(142, Duration.ofMinutes(5), Trend.FLAT));
        snap.noDataMinutes = 30;
        snap.lastCheckCompleted = clock.now();
        assertEquals(FreshnessPresentation.Phase.FRESH, FreshnessPresentation.from(show(snap)).phase());
        clock.advance(Duration.ofMillis(1));
        model.onMinute();
        FreshnessPresentation overdue = FreshnessPresentation.from(model.state());
        assertEquals(FreshnessPresentation.Phase.MISSED, overdue.phase());
        assertEquals(100, overdue.percent());
        assertEquals(0L, overdue.spanMs());
        assertEquals(0, status.retries);
        assertEquals(0, status.refreshes);
    }

    @Test public void aCycleWithNoLengthKeepsAOneSecondSpanAndDoesNotDivideByZero() {
        // CheckCycle lifts `next` to `start`, so a zero-length cycle is possible (R2 review of the Home bar)
        java.time.Instant start = clock.now();
        Snap snap = new Snap().latest(Snap.reading(142, Duration.ofMinutes(1), Trend.FLAT));
        snap.cycle(new app.lightscout.domain.status.CheckCycle(start, start.minusSeconds(30),
                app.lightscout.domain.status.CheckCycle.Kind.NORMAL));
        clock.advance(Duration.ofSeconds(5));
        FreshnessPresentation zero = FreshnessPresentation.from(show(snap));
        assertEquals(1_000L, zero.spanMs());
        assertEquals(1_000L, zero.elapsedMs());
        assertEquals(100, zero.percent());
    }

    @Test public void checkCycleDrivesTheTrackBetweenTwoChecksAndItsColour() {
        java.time.Instant start = clock.now();
        Snap snap = new Snap().latest(Snap.reading(142, Duration.ofMinutes(1), Trend.FLAT));
        snap.noDataMinutes = 30;
        // slow cycle: 4 min 45 s, 20 s in
        clock.advance(Duration.ofSeconds(57));
        snap.cycle(new app.lightscout.domain.status.CheckCycle(start, start.plusSeconds(285),
                app.lightscout.domain.status.CheckCycle.Kind.NORMAL));
        FreshnessPresentation normal = FreshnessPresentation.from(show(snap));
        assertEquals(FreshnessPresentation.Phase.FRESH, normal.phase());
        assertEquals(20, normal.percent());
        assertEquals(57_000L, normal.elapsedMs());
        assertEquals(285_000L, normal.spanMs());
        // first miss without a failure: a short cycle, still the accent colour
        snap.cycle(new app.lightscout.domain.status.CheckCycle(start, start.plusSeconds(60),
                app.lightscout.domain.status.CheckCycle.Kind.WAITING));
        assertEquals(FreshnessPresentation.Phase.WAITING, FreshnessPresentation.from(show(snap)).phase());
        // a failed check or a second miss: orange, never announced as a new phase
        snap.cycle(new app.lightscout.domain.status.CheckCycle(start, start.plusSeconds(60),
                app.lightscout.domain.status.CheckCycle.Kind.MISSED));
        assertEquals(FreshnessPresentation.Phase.MISSED, FreshnessPresentation.from(show(snap)).phase());
        // the cycle is clamped: past the planned check the track stays full
        clock.advance(Duration.ofMinutes(10));
        model.onMinute();
        assertEquals(100, FreshnessPresentation.from(model.state()).percent());
        assertEquals(0, status.retries);
        assertEquals(0, status.refreshes);
    }

    @Test public void noDataCycleIsGreyAndOrangeAndWaitingDoNotRepeatAnnouncements() {
        java.time.Instant start = clock.now();
        Snap snap = new Snap().latest(Snap.reading(142, Duration.ofMinutes(25), Trend.FLAT));
        snap.noDataMinutes = 20;
        snap.cycle(new app.lightscout.domain.status.CheckCycle(start, start.plusSeconds(300),
                app.lightscout.domain.status.CheckCycle.Kind.NO_DATA));
        FreshnessPresentation grey = FreshnessPresentation.from(show(snap));
        assertEquals(FreshnessPresentation.Phase.STALE, grey.phase());
        assertEquals(300_000L, grey.spanMs());
    }

    @Test public void connectivityNeverMakesFreshDataStaleAndThresholdDoesNotDeleteValue() {
        Snap snap = new Snap().latest(Snap.reading(142, Duration.ofMinutes(3), Trend.FLAT))
                .outcome(FetchOutcome.NS_NOT_RESPONDING);
        snap.noDataMinutes = 12;
        snap.lastCheckCompleted = clock.now();
        assertFalse(show(snap).stale());
        assertEquals(BannerKind.NS_NOT_RESPONDING, model.state().banner().kind());
        clock.advance(Duration.ofMinutes(9).minusMillis(1));
        model.onMinute();
        assertFalse(model.state().stale());
        clock.advance(Duration.ofMillis(1));
        model.onMinute();
        assertTrue(model.state().stale());
        assertEquals(Integer.valueOf(142), model.state().sgv());
        assertEquals(FreshnessPresentation.Phase.STALE, FreshnessPresentation.from(model.state()).phase());
        assertFalse(show(new Snap().noReading()).stale());
    }

    @Test public void allPriorityLevelsAndFirstMatchConnectionMappingRemainNamed() {
        Snap snap = new Snap().outcome(FetchOutcome.TOKEN_REJECTED).problem(FetchOutcome.SERVER_TOO_OLD)
                .dataLoss(DataLossCause.UPLOADER, Fakes.T0.minusSeconds(1_200));
        snap.online = false;
        snap.readinessMissing = 1;
        snap.lowEnabled = false;
        snap.alarmsEnabled = false;
        assertEquals(BannerKind.ALARMS_OFF, show(snap).banner().kind());
        snap.alarmsEnabled = true;
        assertEquals(BannerKind.LOW_ALARM_OFF, show(snap).banner().kind());
        snap.lowEnabled = true;
        assertEquals(BannerKind.PERMISSION_MISSING, show(snap).banner().kind());
        snap.readinessMissing = 0;
        assertEquals(BannerKind.VERSION_TOO_OLD, show(snap).banner().kind());
        snap.connectionProblem = null;
        assertEquals(BannerKind.TOKEN_REJECTED, show(snap).banner().kind());
        // offline phone: server errors and the uploader are not blamed, configuration faults still are
        snap.lastOutcome = FetchOutcome.NS_NOT_RESPONDING;
        assertEquals(BannerKind.OFFLINE, show(snap).banner().kind());
        snap.online = true;
        assertEquals(BannerKind.NS_NOT_RESPONDING, show(snap).banner().kind());
        snap.lastOutcome = FetchOutcome.OK;
        assertEquals(BannerKind.DATA_LOSS, show(snap).banner().kind());
        snap.online = false;
        assertEquals(BannerKind.OFFLINE, show(snap).banner().kind());
        assertEquals(BannerKind.OFFLINE, show(new Snap().outcome(FetchOutcome.NO_INTERNET)).banner().kind());
        for (BannerKind kind : BannerKind.values()) {
            assertNotEquals(0, HomeCopy.chipText(kind));
        }
    }

    @Test public void stalePopupCannotRetryOrTurnOnAnotherWarningAndFetchingDisablesCurrentRetry() {
        show(new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING));
        Snap off = new Snap();
        off.alarmsEnabled = false;
        show(off);
        model.onBannerAction(BannerKind.NS_NOT_RESPONDING);
        assertEquals(0, status.retries);
        assertTrue(alarms.calls.isEmpty());
        model.onBannerAction(BannerKind.ALARMS_OFF);
        model.onBannerAction(BannerKind.ALARMS_OFF);
        assertEquals(List.of("alarms:true"), alarms.calls);
        Snap fetching = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        fetching.fetching = true;
        show(fetching);
        model.onBannerAction(BannerKind.NS_NOT_RESPONDING);
        assertEquals(0, status.retries);
        fetching.fetching = false;
        show(fetching);
        model.onBannerAction(BannerKind.NS_NOT_RESPONDING);
        model.onBannerAction(BannerKind.NS_NOT_RESPONDING);
        assertEquals(1, status.retries);
        show(new Snap());
        model.onBannerAction(BannerKind.NS_NOT_RESPONDING);
        assertEquals(1, status.retries);
    }

    @Test public void dockCountersAndStatisticsRouteExplicitlyEvenWithoutData() {
        List<HomeCommand> commands = new ArrayList<>();
        model.setListener(new HomeModel.Listener() {
            public void onState(HomeUiState state) { }
            public void onCommand(HomeCommand command) { commands.add(command); }
        });
        show(new Snap().noReading());
        RecordingNavigator navigator = new RecordingNavigator();
        for (EntryType type : List.of(EntryType.CARBS, EntryType.BOLUS, EntryType.BG_CHECK,
                EntryType.SITE_CHANGE, EntryType.SENSOR_CHANGE)) {
            model.onResume();
            model.onNewEntry(type);
            model.onNewEntry(type);
        }
        model.onResume();
        model.onStatistics();
        commands.forEach(command -> HomeNavigation.execute(command, navigator));
        assertEquals(List.of("newEntry:CARBS", "newEntry:BOLUS", "newEntry:BG_CHECK",
                "newEntry:SITE_CHANGE", "newEntry:SENSOR_CHANGE", "statistics"), navigator.calls);
        assertThrows(NullPointerException.class, () -> model.onNewEntry(null));
        assertThrows(IllegalArgumentException.class, () -> model.onNewEntry(EntryType.PUMP_ALERT));
    }

    @Test public void countersUseExact24HourAndExpiryInstantsRegardlessOfSnapshotState() {
        Instant changed = Instant.parse("2026-10-24T12:00:00Z");
        Instant warning = changed.plus(Duration.ofDays(2));
        Instant expiry = changed.plus(Duration.ofDays(3));
        ConsumableStatus status = new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NORMAL,
                changed, Duration.ZERO, warning, expiry);
        assertEquals(CounterPresentation.State.NORMAL, CounterPresentation.from(status, warning.minusMillis(1)).state());
        assertEquals(CounterPresentation.State.CHANGE_SOON, CounterPresentation.from(status, warning).state());
        assertEquals(CounterPresentation.State.CHANGE_SOON, CounterPresentation.from(status, expiry.minusMillis(1)).state());
        assertEquals(CounterPresentation.State.EXPIRED, CounterPresentation.from(status, expiry).state());
        assertEquals(Duration.ofDays(3), CounterPresentation.from(status, expiry).age());
    }

    @Test public void motionAndOffscreenGuardsAndQuietCachedRefreshPreventBackgroundSweep() {
        assertTrue(FreshnessPresentation.shouldAnimate(true, FreshnessPresentation.Phase.LOADING, true, true));
        assertFalse(FreshnessPresentation.shouldAnimate(false, FreshnessPresentation.Phase.LOADING, true, true));
        assertFalse(FreshnessPresentation.shouldAnimate(true, FreshnessPresentation.Phase.LOADING, false, true));
        assertFalse(FreshnessPresentation.shouldAnimate(true, FreshnessPresentation.Phase.LOADING, true, false));
        assertFalse(FreshnessPresentation.shouldAnimate(true, FreshnessPresentation.Phase.FRESH, true, true));
    }

    @Test public void freshnessAnnouncementsSurviveRecreationAndDeduplicateElapsedMinutes() {
        Snap snap = new Snap().noReading().firstUse();
        snap.lastCheckCompleted = null;
        show(snap);
        assertEquals(FreshnessPresentation.Phase.LOADING, model.takeFreshnessAnnouncement());
        assertNull(model.takeFreshnessAnnouncement());
        snap.latest = Snap.reading(142, Duration.ofMinutes(3), Trend.FLAT);
        snap.lastCheckCompleted = clock.now();
        show(snap);
        assertEquals(FreshnessPresentation.Phase.FRESH, model.takeFreshnessAnnouncement());
        model.setListener(null);
        model.setListener(new HomeModel.Listener() {
            public void onState(HomeUiState state) { }
            public void onCommand(HomeCommand command) { }
        });
        assertNull(model.takeFreshnessAnnouncement());
        clock.advance(Duration.ofMinutes(5));
        model.onMinute();
        assertNull(model.takeFreshnessAnnouncement());
        clock.advance(Duration.ofMinutes(12));
        model.onMinute();
        assertEquals(FreshnessPresentation.Phase.STALE, model.takeFreshnessAnnouncement());
        clock.advance(Duration.ofMinutes(1));
        model.onMinute();
        assertNull(model.takeFreshnessAnnouncement());
    }
}
