package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.feature.home.Fakes.FakeAlarmActions;
import app.lightscout.feature.home.Fakes.FakeClock;
import app.lightscout.feature.home.Fakes.FakeStatusRepository;
import app.lightscout.feature.home.Fakes.FakeTimer;
import app.lightscout.feature.home.Fakes.RecordingNavigator;
import app.lightscout.feature.home.Fakes.Snap;
import app.lightscout.feature.home.HomeUiState.BannerAction;
import app.lightscout.feature.home.HomeUiState.BannerKind;
import app.lightscout.feature.home.HomeUiState.BannerRole;
import app.lightscout.feature.home.HomeUiState.BannerUi;
import app.lightscout.feature.home.HomeUiState.EmptyMessage;
import app.lightscout.feature.home.HomeUiState.ProgressMode;
import app.lightscout.feature.home.HomeUiState.RangeLabel;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * S-01 rules of CP-12 §9 (JVM rows) for the CP-12a parts: banner selection and combined causes, outcome mapping,
 * status block, next-reading indicator, empty states, C-S01-05 loading, minute refresh, banner / toolbar actions →
 * IF-25 / IF-21 / IF-29, GIR-1 and resume (readiness re-check through IF-21 {@code refresh()}).
 */
public class HomeModelTest {

    private FakeClock clock;
    private FakeTimer timer;
    private FakeStatusRepository status;
    private FakeAlarmActions alarms;
    private HomeModel model;
    private final List<HomeUiState> states = new ArrayList<>();
    private final List<HomeCommand> commands = new ArrayList<>();

    @Before
    public void setUp() {
        clock = new FakeClock();
        alarms = new FakeAlarmActions();
        status = new FakeStatusRepository(new Snap().build());
        timer = new FakeTimer();
        model = new HomeModel(status, alarms, clock, timer);
        model.setListener(new HomeModel.Listener() {
            @Override
            public void onState(HomeUiState state) {
                states.add(state);
            }

            @Override
            public void onCommand(HomeCommand command) {
                commands.add(command);
            }
        });
    }

    private HomeUiState show(Snap snap) {
        model.onSnapshot(snap.build());
        return model.state();
    }

    private BannerUi banner(Snap snap) {
        return show(snap).banner();
    }

    private static void assertBanner(BannerUi b, BannerKind kind, BannerAction action, BannerRole role) {
        assertNotNull("banner expected", b);
        assertEquals(kind, b.kind());
        assertEquals(action, b.action());
        assertEquals(role, b.role());
    }

    // --- Banner selection (§7.5, §9 „JVM — banner selection”) ---

    @Test
    public void noCauseShowsNoBanner() {
        assertNull(banner(new Snap()));
    }

    @Test
    public void alarmsOffIsTertiaryWithTurnOn() {
        Snap s = new Snap();
        s.alarmsEnabled = false;
        assertBanner(banner(s), BannerKind.ALARMS_OFF, BannerAction.TURN_ON, BannerRole.TERTIARY);
    }

    @Test
    public void alarmsOffPlusOfflineShowsOnlyAlarmsOff() {
        Snap s = new Snap();
        s.alarmsEnabled = false;
        s.online = false;
        assertBanner(banner(s), BannerKind.ALARMS_OFF, BannerAction.TURN_ON, BannerRole.TERTIARY);
    }

    @Test
    public void lowOffPlusReadinessMissingShowsLowAlarmOff() {
        Snap s = new Snap();
        s.lowEnabled = false;
        s.readinessMissing = 2;
        assertBanner(banner(s), BannerKind.LOW_ALARM_OFF, BannerAction.TURN_ON, BannerRole.TERTIARY);
    }

    @Test
    public void highOffShowsHighAlarmOff() {
        Snap s = new Snap();
        s.highEnabled = false;
        assertBanner(banner(s), BannerKind.HIGH_ALARM_OFF, BannerAction.TURN_ON, BannerRole.TERTIARY);
    }

    @Test
    public void lowAndHighOffShowsCombinedBanner() {
        Snap s = new Snap();
        s.lowEnabled = false;
        s.highEnabled = false;
        assertBanner(banner(s), BannerKind.LOW_HIGH_ALARMS_OFF, BannerAction.TURN_ON, BannerRole.TERTIARY);
    }

    @Test
    public void readinessMissingIsErrorWithFix() {
        Snap s = new Snap();
        s.readinessMissing = 1;
        assertBanner(banner(s), BannerKind.PERMISSION_MISSING, BannerAction.FIX, BannerRole.ERROR);
    }

    @Test
    public void readinessBeatsConnectionError() {
        Snap s = new Snap().outcome(FetchOutcome.TOKEN_REJECTED);
        s.readinessMissing = 1;
        assertEquals(BannerKind.PERMISSION_MISSING, banner(s).kind());
    }

    @Test
    public void connectionErrorBeatsOffline() {
        Snap s = new Snap().problem(FetchOutcome.SERVER_TOO_OLD);
        s.online = false;
        assertEquals(BannerKind.VERSION_TOO_OLD, banner(s).kind());
    }

    // --- Outcome mapping (§9 „JVM — outcome mapping”, CP-00A R-76) ---

    @Test
    public void nsNotRespondingMapsToRetry() {
        assertBanner(banner(new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING)), BannerKind.NS_NOT_RESPONDING,
                BannerAction.RETRY, BannerRole.ERROR);
    }

    @Test
    public void notNightscoutOutcomeMapsToNotNightscoutWithFixConnection() {
        assertBanner(banner(new Snap().outcome(FetchOutcome.NOT_NIGHTSCOUT)), BannerKind.NOT_NIGHTSCOUT,
                BannerAction.FIX_CONNECTION, BannerRole.ERROR);
    }

    @Test
    public void notNightscoutStickyProblemMapsToNotNightscoutWithFixConnection() {
        assertBanner(banner(new Snap().problem(FetchOutcome.NOT_NIGHTSCOUT)), BannerKind.NOT_NIGHTSCOUT,
                BannerAction.FIX_CONNECTION, BannerRole.ERROR);
    }

    @Test
    public void tokenRejectedMapsToFixConnection() {
        assertBanner(banner(new Snap().outcome(FetchOutcome.TOKEN_REJECTED)), BannerKind.TOKEN_REJECTED,
                BannerAction.FIX_CONNECTION, BannerRole.ERROR);
    }

    @Test
    public void permissionMissingMapsToTokenPermissionReadWithFixConnection() {
        assertBanner(banner(new Snap().problem(FetchOutcome.PERMISSION_MISSING)), BannerKind.TOKEN_PERMISSION_READ,
                BannerAction.FIX_CONNECTION, BannerRole.ERROR);
    }

    @Test
    public void writePermissionMissingMapsToTokenPermissionWriteWithFixConnection() {
        assertBanner(banner(new Snap().problem(FetchOutcome.PERMISSION_WRITE_MISSING)),
                BannerKind.TOKEN_PERMISSION_WRITE, BannerAction.FIX_CONNECTION, BannerRole.ERROR);
    }

    @Test
    public void serverTooOldMapsToVersionWithFixConnection() {
        assertBanner(banner(new Snap().problem(FetchOutcome.SERVER_TOO_OLD)), BannerKind.VERSION_TOO_OLD,
                BannerAction.FIX_CONNECTION, BannerRole.ERROR);
    }

    @Test
    public void noInternetShowsOfflineWithDataAge() {
        Snap s = new Snap().outcome(FetchOutcome.NO_INTERNET)
                .latest(Snap.reading(130, Duration.ofMinutes(12).plusSeconds(40), Trend.FLAT));
        BannerUi b = banner(s);
        assertBanner(b, BannerKind.OFFLINE, BannerAction.NONE, BannerRole.SECONDARY);
        assertEquals(12, b.minutes());
    }

    @Test
    public void phoneOfflineShowsOfflineBanner() {
        Snap s = new Snap();
        s.online = false;
        assertEquals(BannerKind.OFFLINE, banner(s).kind());
    }

    @Test
    public void offlineWithoutReadingShowsNoBanner() {
        Snap s = new Snap().noReading().outcome(FetchOutcome.NO_INTERNET);
        assertNull(banner(s));
    }

    @Test
    public void dataLossShowsMinutesSinceAnchorAndRetry() {
        Snap s = new Snap().latest(Snap.reading(110, Duration.ofMinutes(23), Trend.FLAT))
                .dataLoss(DataLossCause.UPLOADER, Fakes.T0.minus(Duration.ofMinutes(23)));
        BannerUi b = banner(s);
        assertBanner(b, BannerKind.DATA_LOSS, BannerAction.RETRY, BannerRole.ERROR);
        assertEquals(23, b.minutes());
    }

    @Test
    public void dataLossAfterFirstUseThresholdUsesMonitoringAnchor() {
        Snap s = new Snap().noReading().dataLoss(DataLossCause.UPLOADER, Fakes.T0.minus(Duration.ofMinutes(31)));
        BannerUi b = banner(s);
        assertEquals(BannerKind.DATA_LOSS, b.kind());
        assertEquals(31, b.minutes());
    }

    @Test
    public void storageErrorShowsNoBanner() {
        assertNull(banner(new Snap().outcome(FetchOutcome.STORAGE_ERROR)));
    }

    // --- Status block (§7.2, §9 „JVM — status block”) ---

    @Test
    public void noReadingShowsEmptyBlock() {
        HomeUiState s = show(new Snap().noReading());
        assertNull(s.sgv());
        assertNull(s.age());
        assertNull(s.trend());
        assertEquals(RangeLabel.NONE, s.rangeLabel());
        assertEquals(ProgressMode.HIDDEN, s.progress().mode());
        assertFalse(s.stale());
    }

    @Test
    public void readingShowsValueTrendAndAge() {
        HomeUiState s = show(new Snap().latest(Snap.reading(142, Duration.ofMinutes(3), Trend.FORTY_FIVE_UP)));
        assertEquals(Integer.valueOf(142), s.sgv());
        assertEquals(Trend.FORTY_FIVE_UP, s.trend());
        assertEquals(Duration.ofMinutes(3), s.age());
    }

    @Test
    public void iobAbsentIsHidden() {
        assertNull(show(new Snap()).iob());
    }

    @Test
    public void iobHasOneDecimal() {
        Snap s = new Snap();
        s.iobCenti = 123;
        assertEquals("1.2", show(s).iob());
        assertEquals("1.3", HomeModel.iobText(125));
        assertEquals("0.1", HomeModel.iobText(5));
        assertEquals("0.0", HomeModel.iobText(0));
        assertEquals("-0.3", HomeModel.iobText(-30));
        assertEquals("12.0", HomeModel.iobText(1200));
    }

    @Test
    public void rangeLabelsWithDefaultThresholds() {
        assertEquals(RangeLabel.URGENT_LOW, label(54));
        assertEquals(RangeLabel.URGENT_LOW, label(55));
        assertEquals(RangeLabel.LOW, label(56));
        assertEquals(RangeLabel.LOW, label(65));
        assertEquals(RangeLabel.LOW, label(70));
        assertEquals(RangeLabel.NONE, label(71));
        assertEquals(RangeLabel.NONE, label(120));
        assertEquals(RangeLabel.NONE, label(249));
        assertEquals(RangeLabel.HIGH, label(250));
        // Q-89: above 250 gets the same HIGH treatment (no very-high badge on S-01).
        assertEquals(RangeLabel.HIGH, label(251));
        assertEquals(RangeLabel.HIGH, label(260));
        assertEquals(RangeLabel.HIGH, label(400));
        assertEquals(4, RangeLabel.values().length);
    }

    @Test
    public void rangeLabelShownRegardlessOfSwitches() {
        Snap s = new Snap().latest(Snap.reading(65, Duration.ofMinutes(1), Trend.FLAT));
        s.lowEnabled = false;
        assertEquals(RangeLabel.LOW, show(s).rangeLabel());
        Snap h = new Snap().latest(Snap.reading(270, Duration.ofMinutes(1), Trend.FLAT));
        h.highEnabled = false;
        assertEquals(RangeLabel.HIGH, show(h).rangeLabel());
    }

    @Test
    public void rangeLabelFollowsCustomThresholds() {
        Snap s = new Snap().latest(Snap.reading(200, Duration.ofMinutes(1), Trend.FLAT));
        s.high = 180;
        assertEquals(RangeLabel.HIGH, show(s).rangeLabel());
        Snap v = new Snap().latest(Snap.reading(260, Duration.ofMinutes(1), Trend.FLAT));
        v.high = 300;
        assertEquals(RangeLabel.NONE, show(v).rangeLabel());
    }

    private RangeLabel label(int sgv) {
        return show(new Snap().latest(Snap.reading(sgv, Duration.ofMinutes(1), Trend.FLAT))).rangeLabel();
    }

    @Test
    public void staleFromDataLossThreshold() {
        assertFalse(show(new Snap().latest(Snap.reading(120, Duration.ofSeconds(19 * 60 + 59), Trend.FLAT)))
                .stale());
        assertTrue(show(new Snap().latest(Snap.reading(120, Duration.ofMinutes(20), Trend.FLAT))).stale());
    }

    @Test
    public void futureDatedReadingWithinToleranceHasZeroAge() {
        HomeUiState s = show(new Snap().latest(Snap.reading(120, Duration.ofSeconds(-90), Trend.FLAT)));
        assertEquals(Duration.ZERO, s.age());
        assertEquals(ProgressMode.DETERMINATE, s.progress().mode());
        assertEquals(0, s.progress().percent());
    }

    // --- Next-reading indicator (§7.7, §9 „JVM — next-reading”) ---

    @Test
    public void twoMinutesAfterReadingIsFortyPercent() {
        HomeUiState s = show(new Snap().latest(Snap.reading(120, Duration.ofMinutes(2), Trend.FLAT)));
        assertEquals(ProgressMode.DETERMINATE, s.progress().mode());
        assertEquals(40, s.progress().percent());
    }

    @Test
    public void overdueIsIndeterminate() {
        Snap s = new Snap().latest(Snap.reading(120, Duration.ofMinutes(2), Trend.FLAT));
        s.overdue = true;
        assertEquals(ProgressMode.OVERDUE, show(s).progress().mode());
    }

    @Test
    public void pastNextExpectedIsOverdueBetweenProjections() {
        Snap s = new Snap().latest(Snap.reading(120, Duration.ofMinutes(4), Trend.FLAT));
        model.onSnapshot(s.build());
        assertEquals(ProgressMode.DETERMINATE, model.state().progress().mode());
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        model.onMinute();
        assertEquals(ProgressMode.OVERDUE, model.state().progress().mode());
    }

    // --- Empty states (§7.6) ---

    @Test
    public void firstUseShowsWaitingMessage() {
        assertEquals(EmptyMessage.FIRST_USE, show(new Snap().noReading().firstUse()).emptyMessage());
    }

    @Test
    public void noReadingAfterFirstUseShowsNoReadings24h() {
        assertEquals(EmptyMessage.NO_READINGS_24H, show(new Snap().noReading()).emptyMessage());
    }

    @Test
    public void readingOlderThan24hShowsNoReadings24h() {
        Snap s = new Snap().latest(Snap.reading(120, Duration.ofHours(25), Trend.FLAT))
                .dataLoss(DataLossCause.UPLOADER, Fakes.T0.minus(Duration.ofHours(25)));
        HomeUiState st = show(s);
        assertEquals(EmptyMessage.NO_READINGS_24H, st.emptyMessage());
        assertEquals(BannerKind.DATA_LOSS, st.banner().kind());
        assertEquals(Integer.valueOf(120), st.sgv());
    }

    @Test
    public void recentReadingShowsNoEmptyMessage() {
        assertEquals(EmptyMessage.NONE, show(new Snap()).emptyMessage());
    }

    @Test
    public void supportPromptGate_calmOnlyWithAFreshReadingAndNothingWrong() {
        assertTrue(SupportPromptGate.isCalm(show(new Snap())));
        assertFalse(SupportPromptGate.isCalm(show(new Snap().noReading())));
        assertFalse(SupportPromptGate.isCalm(show(new Snap().latest(
                Snap.reading(120, Duration.ofMinutes(40), app.lightscout.domain.model.Trend.FLAT)))));
    }

    @Test
    public void supportPromptGate_notCalmWithAFaultBannerARingingAlarmOrASnooze() {
        assertFalse(SupportPromptGate.isCalm(show(new Snap().problem(app.lightscout.domain.port.FetchOutcome.NO_INTERNET))));
        assertFalse(SupportPromptGate.isCalm(show(new Snap().outcome(app.lightscout.domain.port.FetchOutcome.TOKEN_REJECTED))));
        Snap ringing = new Snap();
        ringing.ringing = app.lightscout.domain.alarm.AlarmType.LOW;
        assertFalse(SupportPromptGate.isCalm(show(ringing)));
        Snap snoozed = new Snap();
        snoozed.snoozes = java.util.Map.of(app.lightscout.domain.alarm.AlarmType.HIGH, Fakes.T0.plus(Duration.ofMinutes(30)));
        assertFalse(SupportPromptGate.isCalm(show(snoozed)));
    }

    @Test
    public void consumableCounterLoadingStateIsNotShownAsNoRecords() {
        HomeUiState loading = show(new Snap().site(ConsumableState.LOADING, null));
        assertEquals(CounterPresentation.State.LOADING, loading.siteCounter().state());
        assertNull(loading.siteCounter().age());
    }

    @Test
    public void consumableCountersMapMissingWarningExpiredAndJustChangedStates() {
        HomeUiState missing = show(new Snap());
        assertEquals(CounterPresentation.State.NONE_FOUND, missing.siteCounter().state());
        assertNull(missing.siteCounter().age());

        HomeUiState warning = show(new Snap().site(ConsumableState.CHANGE_SOON,
                Duration.ofDays(2).plusHours(5)));
        assertEquals(CounterPresentation.State.CHANGE_SOON, warning.siteCounter().state());
        assertEquals(Duration.ofDays(2).plusHours(5), warning.siteCounter().age());

        HomeUiState expired = show(new Snap().sensor(ConsumableState.EXPIRED, Duration.ofDays(10)));
        assertEquals(CounterPresentation.State.EXPIRED, expired.sensorCounter().state());
        assertEquals(Duration.ofDays(10), expired.sensorCounter().age());

        HomeUiState justChanged = show(new Snap().site(ConsumableState.NORMAL, Duration.ZERO));
        assertEquals(CounterPresentation.State.NORMAL, justChanged.siteCounter().state());
        assertEquals(Duration.ZERO, justChanged.siteCounter().age());
    }

    @Test
    public void minuteRefreshAdvancesConsumableAgeAndRecomputesWarningAndExpiry() {
        Instant changed = Fakes.T0.minus(Duration.ofDays(2).minus(Duration.ofHours(1)));
        Snap snap = new Snap();
        snap.siteStatus = new app.lightscout.domain.consumable.ConsumableStatus(
                app.lightscout.domain.consumable.ConsumableKind.SITE,
                ConsumableState.NORMAL,
                changed,
                Duration.between(changed, Fakes.T0),
                changed.plus(Duration.ofDays(2)),
                changed.plus(Duration.ofDays(3)));
        model.onSnapshot(snap.build());
        assertEquals(CounterPresentation.State.NORMAL, model.state().siteCounter().state());
        assertEquals(Duration.ofDays(1).plusHours(23), model.state().siteCounter().age());

        clock.advance(Duration.ofHours(2));
        model.onMinute();
        assertEquals(CounterPresentation.State.CHANGE_SOON, model.state().siteCounter().state());
        assertEquals(Duration.ofDays(2).plusHours(1), model.state().siteCounter().age());

        clock.advance(Duration.ofHours(23));
        model.onMinute();
        assertEquals(CounterPresentation.State.EXPIRED, model.state().siteCounter().state());
        assertEquals(Duration.ofDays(3), model.state().siteCounter().age());
    }

    @Test
    public void doughnutStatisticsAreDeliveredToDisplayState() {
        model.onSnapshot(new Snap().build());
        model.onStatisticsLoading();
        assertFalse(model.state().statisticsLoaded());
        assertNull(model.state().statistics());
        app.lightscout.domain.statistics.Statistics statistics =
                new app.lightscout.domain.statistics.Statistics(202, 288, 202d / 288d, false, false,
                        0, 25, 50, 25, 0, 120, 20, 6.1);
        model.onStatistics(statistics);
        assertTrue(model.state().statisticsLoaded());
        assertEquals(statistics, model.state().statistics());
    }

    // --- C-S01-05 loading (CP-00A R-76) ---

    @Test
    public void loadingUntilTickCompletedAfterCreation() {
        Snap cached = new Snap();
        cached.lastCheckCompleted = Fakes.T0.minusSeconds(600);
        HomeUiState s = show(cached);
        assertTrue(s.loading());
        assertEquals(Integer.valueOf(120), s.sgv());   // cached data shown immediately (I-30)

        Snap done = new Snap();
        done.lastCheckCompleted = Fakes.T0.plusSeconds(2);
        assertFalse(show(done).loading());

        assertFalse("loading ends once", show(cached).loading());
    }

    @Test
    public void loadingWithoutCacheShowsDash() {
        Snap s = new Snap().noReading().firstUse();
        s.lastCheckCompleted = null;
        HomeUiState st = show(s);
        assertTrue(st.loading());
        assertNull(st.sgv());
    }

    @Test
    public void tickCompletedAtCreationInstantEndsLoading() {
        Snap s = new Snap();
        s.lastCheckCompleted = Fakes.T0;
        assertFalse(show(s).loading());
    }

    // --- Minute refresh (ADR-049) ---

    @Test
    public void minuteRefreshAdvancesAgeAndProgress() {
        show(new Snap().latest(Snap.reading(120, Duration.ofMinutes(2), Trend.FLAT)));
        clock.advance(Duration.ofMinutes(1));
        model.onMinute();
        assertEquals(Duration.ofMinutes(3), model.state().age());
        assertEquals(60, model.state().progress().percent());
    }

    @Test
    public void progressCarriesTheTimingTheViewNeedsToFillSmoothlyBetweenStates() {
        show(new Snap().latest(Snap.reading(120, Duration.ofMinutes(2), Trend.FLAT)));
        HomeUiState.Progress p = model.state().progress();
        assertEquals(Duration.ofMinutes(2).toMillis(), p.elapsedMs());
        assertEquals(Duration.ofMinutes(5).toMillis(), p.spanMs());
        FreshnessPresentation f = FreshnessPresentation.from(model.state());
        assertEquals(FreshnessPresentation.Phase.FRESH, f.phase());
        assertEquals(p.elapsedMs(), f.elapsedMs());
        assertEquals(p.spanMs(), f.spanMs());
        assertEquals(0L, HomeUiState.Progress.OVERDUE.spanMs());
    }

    @Test
    public void minuteRefreshAdvancesOfflineMinutes() {
        Snap s = new Snap().outcome(FetchOutcome.NO_INTERNET)
                .latest(Snap.reading(120, Duration.ofMinutes(12), Trend.FLAT));
        show(s);
        clock.advance(Duration.ofMinutes(2));
        model.onMinute();
        assertEquals(14, model.state().banner().minutes());
    }

    @Test
    public void refreshDelayFollowsAgeMinuteBoundary() {
        show(new Snap().latest(Snap.reading(120, Duration.ofSeconds(150), Trend.FLAT)));
        assertEquals(30_000L, model.nextRefreshDelayMs());
        show(new Snap().noReading());
        assertEquals(60_000L, model.nextRefreshDelayMs());
        show(new Snap().latest(Snap.reading(120, Duration.ofSeconds(-20), Trend.FLAT)));
        assertEquals(20_000L, model.nextRefreshDelayMs());
        show(new Snap().latest(Snap.reading(120, Duration.ofMillis(59_500), Trend.FLAT)));
        assertEquals(1_000L, model.nextRefreshDelayMs());
    }

    // --- Actions → IF-25 / IF-21 / IF-29 (§7.4, §7.5) ---

    @Test
    public void turnOnAlarmsCallsSetAlarmsEnabled() {
        Snap s = new Snap();
        s.alarmsEnabled = false;
        show(s);
        model.onBannerAction();
        assertEquals(List.of("alarms:true"), alarms.calls);
    }

    @Test
    public void turnOnLowCallsLowOnly() {
        Snap s = new Snap();
        s.lowEnabled = false;
        show(s);
        model.onBannerAction();
        assertEquals(List.of("type:LOW:true"), alarms.calls);
    }

    @Test
    public void turnOnHighCallsHighOnly() {
        Snap s = new Snap();
        s.highEnabled = false;
        show(s);
        model.onBannerAction();
        assertEquals(List.of("type:HIGH:true"), alarms.calls);
    }

    @Test
    public void turnOnBothCallsBoth() {
        Snap s = new Snap();
        s.lowEnabled = false;
        s.highEnabled = false;
        show(s);
        model.onBannerAction();
        assertEquals(List.of("type:LOW:true", "type:HIGH:true"), alarms.calls);
    }

    @Test
    public void alarmsOffWithLowOffTurnsOnMasterSwitchOnly() {
        Snap s = new Snap();
        s.alarmsEnabled = false;
        s.lowEnabled = false;
        show(s);
        model.onBannerAction();
        assertEquals(List.of("alarms:true"), alarms.calls);
    }

    @Test
    public void everyActiveFaultGetsItsOwnChipAndAction() {
        Snap s = new Snap();
        s.lowEnabled = false;
        s.readinessMissing = 1;
        show(s.outcome(FetchOutcome.TOKEN_REJECTED));
        assertEquals(List.of(BannerKind.LOW_ALARM_OFF, BannerKind.PERMISSION_MISSING, BannerKind.TOKEN_REJECTED),
                model.state().banners().stream().map(BannerUi::kind).toList());
        assertEquals(BannerKind.LOW_ALARM_OFF, model.state().banner().kind());
        model.onBannerAction(BannerKind.TOKEN_REJECTED);
        assertEquals(List.of(HomeCommand.OPEN_CONNECTION_EDIT), commands);
        assertTrue(alarms.calls.isEmpty());
    }

    @Test
    public void fixOpensReadinessCheck() {
        Snap s = new Snap();
        s.readinessMissing = 1;
        show(s);
        model.onBannerAction();
        assertEquals(List.of(HomeCommand.OPEN_READINESS_CHECK), commands);
        assertEquals(List.of("readiness:CHECK"), navigate(commands));
    }

    @Test
    public void fixConnectionOpensConnectionEdit() {
        show(new Snap().outcome(FetchOutcome.TOKEN_REJECTED));
        model.onBannerAction();
        assertEquals(List.of(HomeCommand.OPEN_CONNECTION_EDIT), commands);
        assertEquals(List.of("connection:EDIT"), navigate(commands));
    }

    @Test
    public void retryCallsStatusRetry() {
        show(new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING));
        model.onBannerAction();
        assertEquals(1, status.retries);
        assertTrue(alarms.calls.isEmpty());
        assertTrue(commands.isEmpty());
    }

    @Test
    public void dataLossRetryCallsStatusRetry() {
        show(new Snap().dataLoss(DataLossCause.UPLOADER, Fakes.T0.minus(Duration.ofMinutes(25)))
                .latest(Snap.reading(100, Duration.ofMinutes(25), Trend.FLAT)));
        model.onBannerAction();
        assertEquals(1, status.retries);
    }

    @Test
    public void offlineBannerHasNoAction() {
        show(new Snap().outcome(FetchOutcome.NO_INTERNET));
        model.onBannerAction();
        assertEquals(0, status.retries);
        assertTrue(commands.isEmpty());
        assertTrue(alarms.calls.isEmpty());
    }

    @Test
    public void toolbarActionsOpenStatisticsAndSettings() {
        model.onSnapshot(new Snap().build());
        model.onStatistics();
        model.onResume();
        model.onSettings();
        assertEquals(List.of(HomeCommand.OPEN_STATISTICS, HomeCommand.OPEN_SETTINGS), commands);
        assertEquals(List.of("statistics", "settings"), navigate(commands));
    }

    @Test
    public void navigationMapsEveryCommand() {
        assertEquals(List.of("statistics", "settings", "newEntry:CARBS", "newEntry:BOLUS", "newEntry:BG_CHECK",
                "newEntry:SITE_CHANGE", "newEntry:SENSOR_CHANGE", "entry:entry-1", "readiness:CHECK",
                        "connection:EDIT"),
                navigate(List.of(HomeCommand.values())));
    }

    @Test
    public void addEntryUsesSingleGuardAndOpensCreateMode() {
        model.onNewEntry(app.lightscout.domain.model.EntryType.CARBS);
        model.onNewEntry(app.lightscout.domain.model.EntryType.CARBS);
        assertEquals(List.of(HomeCommand.ADD_CARBS), commands);
        assertEquals(List.of("newEntry:CARBS"), navigate(commands));
    }

    @Test
    public void chartEditUsesIdentifier() {
        model.onEditEntry("entry-1");
        assertEquals(List.of(HomeCommand.EDIT_ENTRY), commands);
        RecordingNavigator nav = new RecordingNavigator();
        HomeNavigation.execute(commands.get(0), nav, model.takePendingEntryIdentifier());
        assertEquals(List.of("entry:entry-1"), nav.calls);
    }

    private static List<String> navigate(List<HomeCommand> list) {
        RecordingNavigator nav = new RecordingNavigator();
        for (HomeCommand c : list) {
            HomeNavigation.execute(c, nav, c == HomeCommand.EDIT_ENTRY ? "entry-1" : null);
        }
        return nav.calls;
    }

    // --- GIR-1 ---

    @Test
    public void doubleTapStatisticsNavigatesOnce() {
        model.onSnapshot(new Snap().build());
        model.onStatistics();
        model.onStatistics();
        model.onSettings();
        assertEquals(List.of(HomeCommand.OPEN_STATISTICS), commands);
    }

    @Test
    public void doubleTapFixConnectionNavigatesOnce() {
        show(new Snap().outcome(FetchOutcome.SERVER_TOO_OLD));
        model.onBannerAction();
        model.onBannerAction();
        assertEquals(1, commands.size());
        model.onResume();
        model.onBannerAction();
        assertEquals(2, commands.size());
    }

    @Test
    public void doubleTapRetryRequestsOneTickAndDisablesButton() {
        show(new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING));
        assertTrue(model.state().banner().actionEnabled());
        model.onBannerAction();
        model.onBannerAction();
        assertEquals(1, status.retries);
        assertFalse(model.state().banner().actionEnabled());
    }

    @Test
    public void retryDisabledWhileFetchingAndEnabledAfterTick() {
        Instant tap = clock.now();
        show(new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING));
        model.onBannerAction();

        clock.advance(Duration.ofSeconds(1));
        Snap fetching = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        fetching.fetching = true;
        fetching.lastCheckCompleted = tap.minusSeconds(300);
        assertFalse(show(fetching).banner().actionEnabled());
        model.onBannerAction();
        assertEquals(1, status.retries);

        clock.advance(Duration.ofSeconds(3));
        Snap after = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        after.lastCheckCompleted = clock.now();
        assertTrue(show(after).banner().actionEnabled());
    }

    @Test
    public void retryReleasedByNewerCompletedTickWhenFetchingWasCoalesced() {
        show(new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING));
        model.onBannerAction();
        Snap same = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        assertFalse("same completion time: not yet acknowledged", show(same).banner().actionEnabled());
        clock.advance(Duration.ofSeconds(2));
        Snap after = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        after.lastCheckCompleted = clock.now();
        assertTrue("IR-102: postValue may coalesce fetching true → false", show(after).banner().actionEnabled());
        assertFalse("fail-safe cancelled", timer.has(HomeModel.RETRY_FAIL_SAFE_MS));
    }

    @Test
    public void retryReleasedWhenFirstCompletionArrivesWithoutBaseline() {
        Snap never = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        never.lastCheckCompleted = null;
        show(never);
        model.onBannerAction();
        Snap after = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        after.lastCheckCompleted = clock.now();
        assertTrue(show(after).banner().actionEnabled());
    }

    @Test
    public void unacknowledgedRetryIsReleasedByFailSafeTimerAtSixtySeconds() {
        Snap s = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        show(s);
        model.onBannerAction();
        show(s);   // an unrelated projection (e.g. refresh) does not acknowledge the tap
        assertFalse(model.state().banner().actionEnabled());
        assertTrue("own timer at exactly 60 s", timer.has(60_000L));
        model.onMinute();
        assertFalse("minute refresh is not the fail-safe", model.state().banner().actionEnabled());
        timer.runAll(60_000L);
        assertTrue(model.state().banner().actionEnabled());
    }

    @Test
    public void disposeCancelsFailSafes() {
        show(new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING));
        model.onResume();
        model.onBannerAction();
        model.onStatistics();
        model.dispose();
        assertTrue(timer.pending.isEmpty());
    }

    // --- IR-101: navigation without a transition releases the guard ---

    @Test
    public void noOpStatisticsThenFixConnectionNavigates() {
        show(new Snap().outcome(FetchOutcome.TOKEN_REJECTED));
        model.onResume();
        model.onStatistics();             // M1: CP-15 no-op destination, S-01 stays resumed
        model.onNavigationIdle();         // next frame, still resumed
        model.onBannerAction();
        assertEquals(List.of(HomeCommand.OPEN_STATISTICS, HomeCommand.OPEN_CONNECTION_EDIT), commands);
    }

    @Test
    public void navigationFailSafeReleasesGuardWhileResumed() {
        show(new Snap());
        model.onResume();
        model.onSettings();
        assertTrue(timer.has(HomeModel.NAVIGATION_FAIL_SAFE_MS));
        model.onStatistics();
        timer.runAll(HomeModel.NAVIGATION_FAIL_SAFE_MS);
        model.onStatistics();
        assertEquals(List.of(HomeCommand.OPEN_SETTINGS, HomeCommand.OPEN_STATISTICS), commands);
    }

    @Test
    public void startedTransitionKeepsGuardUntilResume() {
        show(new Snap());
        model.onResume();
        model.onSettings();
        model.onPause();                  // the transition paused S-01
        model.onNavigationIdle();
        timer.runAll(HomeModel.NAVIGATION_FAIL_SAFE_MS);
        model.onStatistics();
        assertEquals(List.of(HomeCommand.OPEN_SETTINGS), commands);
        model.onResume();                 // back on S-01
        model.onStatistics();
        assertEquals(List.of(HomeCommand.OPEN_SETTINGS, HomeCommand.OPEN_STATISTICS), commands);
    }

    @Test
    public void dataLossMinutesFloorAtNonWholeOffset() {
        Snap s = new Snap().latest(Snap.reading(110, Duration.ofSeconds(23 * 60 + 40), Trend.FLAT))
                .dataLoss(DataLossCause.UPLOADER, Fakes.T0.minus(Duration.ofSeconds(23 * 60 + 40)));
        assertEquals(23, banner(s).minutes());
        clock.advance(Duration.ofSeconds(19));
        model.onMinute();
        assertEquals(23, model.state().banner().minutes());
        clock.advance(Duration.ofSeconds(1));
        model.onMinute();
        assertEquals(24, model.state().banner().minutes());
    }

    @Test
    public void retryDisabledWhenSnapshotFetching() {
        Snap s = new Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
        s.fetching = true;
        assertFalse(show(s).banner().actionEnabled());
        model.onBannerAction();
        assertEquals(0, status.retries);
    }

    @Test
    public void doubleTapTurnOnCallsOnceUntilNextSnapshot() {
        Snap s = new Snap();
        s.alarmsEnabled = false;
        show(s);
        model.onBannerAction();
        model.onBannerAction();
        assertEquals(1, alarms.calls.size());
        assertFalse(model.state().banner().actionEnabled());
        show(s);
        assertTrue(model.state().banner().actionEnabled());
    }

    // --- Lifecycle (§7.10; CP-00A R-76) ---

    @Test
    public void resumeRefreshesStatusForReadiness() {
        model.onSnapshot(new Snap().build());
        model.onResume();
        model.onResume();
        assertEquals(2, status.refreshes);
    }

    @Test
    public void readinessBannerFollowsSnapshotAfterResume() {
        show(new Snap());
        assertNull(model.state().banner());
        model.onResume();
        Snap missing = new Snap();
        missing.readinessMissing = 1;
        assertEquals(BannerKind.PERMISSION_MISSING, show(missing).banner().kind());
        model.onResume();
        assertNull(show(new Snap()).banner());
    }

    @Test
    public void listenerReceivesCurrentStateWhenSet() {
        model.onSnapshot(new Snap().build());
        List<HomeUiState> late = new ArrayList<>();
        model.setListener(new HomeModel.Listener() {
            @Override
            public void onState(HomeUiState state) {
                late.add(state);
            }

            @Override
            public void onCommand(HomeCommand command) {
                // not used
            }
        });
        assertEquals(1, late.size());
    }

    @Test
    public void noStateBeforeFirstSnapshot() {
        HomeModel fresh = new HomeModel(status, alarms, clock, timer);
        assertNull(fresh.state());
        fresh.onBannerAction();
        fresh.onMinute();
        assertNull(fresh.state());
        assertEquals(0, status.retries);
    }

    @Test
    public void stateTracksSnapshotsInOrder() {
        StatusSnapshot a = new Snap().build();
        Snap b = new Snap();
        b.readinessMissing = 1;
        model.onSnapshot(a);
        model.onSnapshot(b.build());
        assertEquals(2, states.size());
        assertNull(states.get(0).banner());
        assertEquals(BannerKind.PERMISSION_MISSING, states.get(1).banner().kind());
    }
}
