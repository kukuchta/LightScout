package app.lightscout.domain.alarm;

import app.lightscout.domain.FakeClock;
import app.lightscout.domain.TestSettings;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.dataloss.DataLossClassifier;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.entry.ReadingRules;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.settings.Settings;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TST-01 alarm decision table (CP-01 §9: AL-1…AL-9, F2, F12, F20, M-9, M-10; CP-00A R-60).
 * Defaults: UL 55 / Low 70 / High 250 / No data 20 / snooze 15·15·60·30. Inputs are built the way CP-02 builds
 * them: data loss through IF-02, watchdog through IF-03 on {@code lastCheckCompleted}.
 */
public class AlarmEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-10-15T12:00:00Z");

    private final AlarmEvaluator evaluator = new AlarmEvaluator();
    private final DataLossClassifier classifier = new DataLossClassifier();
    private final WatchdogPolicy watchdog = new WatchdogPolicy();

    private FakeClock clock;
    private Settings settings;
    private AlarmState state;
    private Reading latest;
    private Reading futureDatedLow;
    private Instant lastCheckCompleted;
    private Instant monitoringSince;
    private FetchOutcome lastOutcome;
    private int readingSeq;

    @Before
    public void setUp() {
        clock = new FakeClock(T0);
        settings = Settings.defaults();
        state = AlarmState.empty();
        latest = null;
        futureDatedLow = null;
        lastCheckCompleted = T0.minus(Duration.ofMinutes(1));
        monitoringSince = T0.minus(Duration.ofDays(1));
        lastOutcome = FetchOutcome.OK;
    }

    // ---- helpers ----

    private Reading reading(int sgv, Duration age) {
        readingSeq++;
        return new Reading("r" + readingSeq, clock.now().minus(age), sgv, Trend.FLAT, "test");
    }

    private void fresh(int sgv) {
        latest = reading(sgv, Duration.ofMinutes(2));
    }

    /** Moves time to {@code t} with a live monitoring loop (a check completed a minute ago). */
    private void at(Instant t) {
        clock.set(t);
        lastCheckCompleted = t.minus(Duration.ofMinutes(1));
    }

    private AlarmDecision evaluate() {
        Instant now = clock.now();
        DataLossStatus dataLoss = classifier.classify(now, latest == null ? null : latest.time(), monitoringSince,
                lastOutcome, settings.dataLossThreshold());
        boolean stale = watchdog.isNotRunning(now, lastCheckCompleted, settings.dataLossThreshold());
        return evaluator.evaluate(new AlarmInputs(now, latest, futureDatedLow, settings, dataLoss, stale,
                lastCheckCompleted, state));
    }

    private static AlarmType startedType(AlarmDecision d) {
        assertEquals("one command expected: " + d.commands(), 1, d.commands().size());
        AlarmCommand c = d.commands().get(0);
        assertTrue("Start expected: " + c, c instanceof AlarmCommand.Start);
        return ((AlarmCommand.Start) c).type();
    }

    private static AlarmCommand.Start onlyStart(AlarmDecision d) {
        startedType(d);
        return (AlarmCommand.Start) d.commands().get(0);
    }

    private static void assertNone(AlarmDecision d) {
        assertTrue("no command expected: " + d.commands(), d.commands().isEmpty());
    }

    private static void assertNoStop(AlarmDecision d) {
        for (AlarmCommand c : d.commands()) {
            assertFalse("unexpected Stop: " + c, c instanceof AlarmCommand.Stop);
        }
    }

    private static AlarmCommand.Update onlyUpdate(AlarmDecision d) {
        assertEquals("one command expected: " + d.commands(), 1, d.commands().size());
        AlarmCommand c = d.commands().get(0);
        assertTrue("Update expected: " + c, c instanceof AlarmCommand.Update);
        return (AlarmCommand.Update) c;
    }

    private static AlarmCommand.Replace onlyReplace(AlarmDecision d) {
        assertEquals("one command expected: " + d.commands(), 1, d.commands().size());
        AlarmCommand c = d.commands().get(0);
        assertTrue("Replace expected: " + c, c instanceof AlarmCommand.Replace);
        return (AlarmCommand.Replace) c;
    }

    private void ringing(AlarmType type) {
        Map<AlarmType, AlarmTypeState> perType = new EnumMap<>(AlarmType.class);
        perType.putAll(state.perType());
        perType.put(type, new AlarmTypeState(clock.now().minus(Duration.ofMinutes(1)), null, 0, null));
        state = new AlarmState(type, perType, null);
    }

    private void snoozed(AlarmType type, Instant until) {
        Map<AlarmType, AlarmTypeState> perType = new EnumMap<>(AlarmType.class);
        perType.putAll(state.perType());
        perType.put(type, new AlarmTypeState(null, until, 0, null));
        AlarmType active = state.activeType() == type ? null : state.activeType();
        state = new AlarmState(active, perType, null);
    }

    // ---- AL-1 ----

    @Test
    public void al1a_sgv54Age2_allOn_startsUrgentLow() {
        fresh(54);
        AlarmCommand.Start start = onlyStart(evaluate());
        assertEquals(AlarmType.URGENT_LOW, start.type());
        assertEquals(Integer.valueOf(54), start.view().sgv());
        assertEquals(15, start.view().snoozeMinutes());
    }

    @Test
    public void al1b_boundaries_urgentLowAndLow() {
        fresh(55);
        assertEquals(AlarmType.URGENT_LOW, startedType(evaluate()));
        fresh(56);
        assertEquals(AlarmType.LOW, startedType(evaluate()));
        fresh(70);
        assertEquals(AlarmType.LOW, startedType(evaluate()));
        fresh(71);
        assertNone(evaluate());
    }

    @Test
    public void al1c_boundaries_high() {
        fresh(250);
        AlarmCommand.Start start = onlyStart(evaluate());
        assertEquals(AlarmType.HIGH, start.type());
        assertEquals(60, start.view().snoozeMinutes());
        fresh(249);
        assertNone(evaluate());
    }

    @Test
    public void al1d_staleReadingNeverFiresGlucoseAlarm() {
        latest = reading(60, Duration.ofMinutes(15));
        assertEquals(AlarmType.LOW, startedType(evaluate()));
        latest = reading(60, Duration.ofMinutes(16));
        assertNone(evaluate());
    }

    // ---- AL-2 / F2 ----

    @Test
    public void al2a_noDataAtThresholdExactly() {
        latest = reading(120, Duration.ofMinutes(19).plusSeconds(59));
        assertNone(evaluate());
        latest = reading(120, Duration.ofMinutes(20));
        AlarmCommand.Start start = onlyStart(evaluate());
        assertEquals(AlarmType.NO_DATA, start.type());
        assertEquals(DataLossCause.UPLOADER, start.view().cause());
        assertEquals(latest.time(), start.view().dataLossSince());
        assertNull(start.view().sgv());
        assertEquals(30, start.view().snoozeMinutes());
    }

    @Test
    public void al2b_f2_outageWithLiveLoop_raisesNoDataOnly() {
        lastOutcome = FetchOutcome.NS_NOT_RESPONDING;
        lastCheckCompleted = clock.now().minus(Duration.ofSeconds(30));   // loop alive
        latest = reading(120, Duration.ofMinutes(25));                    // no reading for 25 min
        AlarmCommand.Start start = onlyStart(evaluate());
        assertEquals(AlarmType.NO_DATA, start.type());
        assertEquals(DataLossCause.NS_NOT_RESPONDING, start.view().cause());

        // later in the same outage the loop keeps running: still No data, never NOT_RUNNING
        ringing(AlarmType.NO_DATA);
        clock.advance(Duration.ofMinutes(30));
        lastCheckCompleted = clock.now().minus(Duration.ofSeconds(30));
        AlarmDecision later = evaluate();
        assertEquals(AlarmType.NO_DATA, onlyUpdate(later).view().type());
    }

    // ---- AL-3 ----

    @Test
    public void al3a_lowSwitchOff_urgentLowStillRings() {
        settings = TestSettings.defaults().low(false).build();
        fresh(60);
        assertNone(evaluate());
        fresh(50);
        assertEquals(AlarmType.URGENT_LOW, startedType(evaluate()));
    }

    @Test
    public void al3b_highSwitchOff() {
        settings = TestSettings.defaults().high(false).build();
        fresh(300);
        assertNone(evaluate());
    }

    @Test
    public void al3c_d022_alarmsOff_silencesEveryType() {
        settings = TestSettings.defaults().alarms(false).build();
        fresh(40);
        assertNone(evaluate());
        latest = reading(120, Duration.ofMinutes(25));
        assertNone(evaluate());
        lastCheckCompleted = clock.now().minus(Duration.ofMinutes(25));
        assertNone(evaluate());
    }

    @Test
    public void al3d_d022_alarmsTurnedOn_ringsInSameEvaluation() {
        settings = TestSettings.defaults().alarms(false).build();
        fresh(60);
        assertNone(evaluate());
        settings = TestSettings.defaults().alarms(true).build();
        assertEquals(AlarmType.LOW, startedType(evaluate()));
    }

    // ---- AL-4 ----

    @Test
    public void al4_ringingLow_valueRecovers_noStop_updateOnly() {
        ringing(AlarmType.LOW);
        fresh(120);
        AlarmDecision d = evaluate();
        assertNoStop(d);
        AlarmCommand.Update update = onlyUpdate(d);
        assertEquals(AlarmType.LOW, update.view().type());
        assertEquals(Integer.valueOf(120), update.view().sgv());
    }

    // ---- AL-5 ----

    @Test
    public void al5a_snoozeExpiry_reRingsOnlyIfConditionHolds() {
        Instant t = clock.now();
        snoozed(AlarmType.LOW, t.plus(Duration.ofMinutes(15)));

        at(t.plus(Duration.ofMinutes(5)));
        fresh(60);
        assertNone(evaluate());

        at(t.plus(Duration.ofMinutes(15)));
        fresh(60);
        assertEquals(AlarmType.LOW, startedType(evaluate()));

        fresh(100);
        assertNone(evaluate());
    }

    @Test
    public void al5b_snoozedUntilAppearsInNextEvaluation() {
        Instant until = clock.now().plus(Duration.ofMinutes(15));
        snoozed(AlarmType.LOW, until);
        latest = reading(60, Duration.ZERO);   // data-loss instant = now + 20 min
        assertEquals(until, evaluate().nextEvaluation());
    }

    // ---- AL-6 ----

    @Test
    public void al6a_highRinging_readingsStop_replacedByNoData() {
        ringing(AlarmType.HIGH);
        latest = reading(300, Duration.ofMinutes(20));
        AlarmCommand.Replace replace = onlyReplace(evaluate());
        assertEquals(AlarmType.HIGH, replace.fromType());
        assertEquals(AlarmType.NO_DATA, replace.toType());
        assertEquals(AlarmType.NO_DATA, replace.view().type());
    }

    @Test
    public void al6b_lowReplacedByUrgentLow_thenPendingLowStartsAfterSnooze() {
        ringing(AlarmType.LOW);
        fresh(50);
        AlarmCommand.Replace replace = onlyReplace(evaluate());
        assertEquals(AlarmType.LOW, replace.fromType());
        assertEquals(AlarmType.URGENT_LOW, replace.toType());

        // IF-25 snooze of URGENT_LOW (R-29): snoozedUntil set, activeType cleared, then re-evaluate
        ringing(AlarmType.URGENT_LOW);
        snoozed(AlarmType.URGENT_LOW, clock.now().plus(Duration.ofMinutes(15)));
        fresh(65);
        assertEquals(AlarmType.LOW, startedType(withoutClears(evaluate())));
    }

    @Test
    public void al6c_noDataRinging_watchdogStale_notRunningDoesNotReplace() {
        ringing(AlarmType.NO_DATA);
        latest = reading(120, Duration.ofMinutes(25));
        lastCheckCompleted = clock.now().minus(Duration.ofMinutes(25));
        AlarmDecision d = evaluate();
        assertEquals(AlarmType.NO_DATA, onlyUpdate(d).view().type());
    }

    @Test
    public void al6d_afterHigherSnoozed_pendingConditionGone_nothingRings() {
        ringing(AlarmType.URGENT_LOW);
        snoozed(AlarmType.URGENT_LOW, clock.now().plus(Duration.ofMinutes(15)));
        fresh(100);
        assertEquals(List.of(new AlarmCommand.ClearSnooze(AlarmType.URGENT_LOW)), evaluate().commands());
    }

    @Test
    public void al6_ir12_lowRinging_conditionCleared_lowerHighEligible_noReplace() {
        ringing(AlarmType.LOW);
        fresh(260);
        AlarmDecision d = evaluate();
        assertEquals(AlarmType.LOW, onlyUpdate(d).view().type());
    }

    @Test
    public void al6_ir12_noDataRinging_dataReturnsHigh_noReplace() {
        ringing(AlarmType.NO_DATA);
        fresh(300);
        AlarmDecision d = evaluate();
        assertEquals(AlarmType.NO_DATA, onlyUpdate(d).view().type());
    }

    @Test
    public void al1_ir13_urgentLowSnoozed_lowDoesNotRingAtOrBelowUrgentLow() {
        snoozed(AlarmType.URGENT_LOW, clock.now().plus(Duration.ofMinutes(15)));
        fresh(50);
        assertNone(evaluate());
        fresh(55);
        assertNone(evaluate());
        fresh(56);
        assertEquals(AlarmType.LOW, startedType(withoutClears(evaluate())));
    }

    @Test
    public void f20_ir14_noDataSnoozed_watchdogStale_notRunningStarts() {
        snoozed(AlarmType.NO_DATA, clock.now().plus(Duration.ofMinutes(30)));
        latest = reading(120, Duration.ofMinutes(25));
        lastCheckCompleted = clock.now().minus(Duration.ofMinutes(25));
        assertEquals(AlarmType.NOT_RUNNING, startedType(evaluate()));
    }

    // ---- AL-7 ----

    @Test
    public void al7_noHysteresis_newLowRingsImmediately() {
        fresh(71);
        assertNone(evaluate());
        fresh(70);
        assertEquals(AlarmType.LOW, startedType(evaluate()));

        // snooze expiry, recovery, then a new low rings at once
        Instant t = clock.now();
        snoozed(AlarmType.LOW, t.plus(Duration.ofMinutes(15)));
        at(t.plus(Duration.ofMinutes(20)));
        fresh(100);
        assertNone(evaluate());
        at(t.plus(Duration.ofMinutes(25)));
        fresh(69);
        assertEquals(AlarmType.LOW, startedType(evaluate()));
    }

    // ---- AL-8 ----

    @Test
    public void al8_lowSnoozed_doesNotSuppressUrgentLow() {
        snoozed(AlarmType.LOW, clock.now().plus(Duration.ofMinutes(15)));
        fresh(50);
        AlarmDecision d = evaluate();
        assertEquals(List.of(new AlarmCommand.ClearSnooze(AlarmType.LOW)), clears(d));
        assertEquals(AlarmType.URGENT_LOW, onlyStart(withoutClears(d)).type());
    }

    // ---- AL-5a snooze auto-clear (D-127 / D-128) ----

    private static List<AlarmCommand> clears(AlarmDecision d) {
        return d.commands().stream().filter(c -> c instanceof AlarmCommand.ClearSnooze).toList();
    }

    private static AlarmDecision withoutClears(AlarmDecision d) {
        return new AlarmDecision(d.commands().stream()
                .filter(c -> !(c instanceof AlarmCommand.ClearSnooze)).toList(), d.nextEvaluation());
    }

    @Test
    public void al5a_freshReadingInRange_clearsHighSnooze_andNothingRings() {
        snoozed(AlarmType.HIGH, clock.now().plus(Duration.ofMinutes(30)));
        fresh(180);
        AlarmDecision d = evaluate();
        assertEquals(List.of(new AlarmCommand.ClearSnooze(AlarmType.HIGH)), d.commands());
    }

    @Test
    public void al5a_staleReading_neverClearsSnooze() {
        snoozed(AlarmType.HIGH, clock.now().plus(Duration.ofMinutes(30)));
        latest = reading(180, Duration.ofMinutes(16));
        assertTrue(clears(evaluate()).isEmpty());
    }

    @Test
    public void al5a_readingStillOutOfRange_keepsSnooze() {
        snoozed(AlarmType.HIGH, clock.now().plus(Duration.ofMinutes(30)));
        fresh(300);
        assertNone(evaluate());
    }

    @Test
    public void al5a_clearedHigh_ringsAgainOnNextEntry() {
        snoozed(AlarmType.HIGH, clock.now().plus(Duration.ofMinutes(30)));
        fresh(180);
        assertEquals(List.of(new AlarmCommand.ClearSnooze(AlarmType.HIGH)), evaluate().commands());
        state = AlarmState.empty();
        at(clock.now().plus(Duration.ofMinutes(5)));
        fresh(260);
        assertEquals(AlarmType.HIGH, startedType(evaluate()));
    }

    @Test
    public void al5a_noDataSnooze_clearsWhenDataLossEnds() {
        snoozed(AlarmType.NO_DATA, clock.now().plus(Duration.ofMinutes(30)));
        fresh(120);
        assertEquals(List.of(new AlarmCommand.ClearSnooze(AlarmType.NO_DATA)), evaluate().commands());
    }

    @Test
    public void al5a_noDataSnooze_keptWhileDataLossLasts() {
        snoozed(AlarmType.NO_DATA, clock.now().plus(Duration.ofMinutes(30)));
        latest = reading(120, Duration.ofMinutes(25));
        assertTrue(clears(evaluate()).isEmpty());
    }

    @Test
    public void al5a_notRunningSnooze_clearsOnFreshWatchdog_keptWhileStale() {
        snoozed(AlarmType.NOT_RUNNING, clock.now().plus(Duration.ofMinutes(30)));
        fresh(120);
        assertEquals(List.of(new AlarmCommand.ClearSnooze(AlarmType.NOT_RUNNING)), evaluate().commands());
        lastCheckCompleted = clock.now().minus(Duration.ofMinutes(40));
        assertTrue(clears(evaluate()).isEmpty());
    }

    @Test
    public void al5a_clearedSnooze_isNotInNextEvaluation() {
        Instant until = clock.now().plus(Duration.ofMinutes(10));
        snoozed(AlarmType.HIGH, until);
        fresh(180);
        assertFalse(until.equals(evaluate().nextEvaluation()));
    }

    // ---- AL-9 ----

    @Test
    public void al9_restoredRingingState_keepsRinging_noStartDuplicate_noStop() {
        ringing(AlarmType.LOW);
        fresh(65);
        AlarmDecision d = evaluate();
        assertNoStop(d);
        assertEquals(AlarmType.LOW, onlyUpdate(d).view().type());
    }

    // ---- R-60 / D-035 switch-off while ringing ----

    @Test
    public void r60_lowSwitchedOffWhileRinging_stopsLow() {
        ringing(AlarmType.LOW);
        settings = TestSettings.defaults().low(false).build();
        fresh(60);
        assertEquals(List.of(new AlarmCommand.Stop(AlarmType.LOW)), evaluate().commands());
    }

    @Test
    public void r60_highSwitchedOffWhileRinging_stopsHigh() {
        ringing(AlarmType.HIGH);
        settings = TestSettings.defaults().high(false).build();
        fresh(300);
        assertEquals(List.of(new AlarmCommand.Stop(AlarmType.HIGH)), evaluate().commands());
    }

    @Test
    public void r60_alarmsSwitchedOffWhileRinging_stopsEvenUrgentLow() {
        ringing(AlarmType.URGENT_LOW);
        settings = TestSettings.defaults().alarms(false).build();
        fresh(40);
        assertEquals(List.of(new AlarmCommand.Stop(AlarmType.URGENT_LOW)), evaluate().commands());
    }

    @Test
    public void r60_lowSwitchedOff_urgentLowConditionStartsAfterStop() {
        ringing(AlarmType.LOW);
        settings = TestSettings.defaults().low(false).build();
        fresh(50);
        List<AlarmCommand> commands = evaluate().commands();
        assertEquals(2, commands.size());
        assertEquals(new AlarmCommand.Stop(AlarmType.LOW), commands.get(0));
        assertEquals(AlarmType.URGENT_LOW, ((AlarmCommand.Start) commands.get(1)).type());
    }

    // ---- F20 ----

    @Test
    public void f20a_notRunning_snoozeIsNoDataSnooze() {
        fresh(120);
        lastCheckCompleted = clock.now().minus(Duration.ofMinutes(25));
        AlarmCommand.Start start = onlyStart(evaluate());
        assertEquals(AlarmType.NOT_RUNNING, start.type());
        assertEquals(30, start.view().snoozeMinutes());
        assertEquals(lastCheckCompleted, start.view().lastCheck());

        settings = TestSettings.defaults().noDataSnooze(45).build();
        assertEquals(45, onlyStart(evaluate()).view().snoozeMinutes());
    }

    @Test
    public void f20b_notRunning_gatedByAlarmsSwitch() {
        settings = TestSettings.defaults().alarms(false).build();
        fresh(120);
        lastCheckCompleted = clock.now().minus(Duration.ofMinutes(25));
        assertNone(evaluate());
    }

    @Test
    public void f20_notRunning_snoozed_doesNotRing() {
        fresh(120);
        lastCheckCompleted = clock.now().minus(Duration.ofMinutes(25));
        snoozed(AlarmType.NOT_RUNNING, clock.now().plus(Duration.ofMinutes(30)));
        assertNone(evaluate());
    }

    // ---- F12 ----

    @Test
    public void f12_nextEvaluation_isMinOfDataLossInstantAndSnoozes() {
        Instant now = clock.now();
        latest = reading(300, Duration.ofMinutes(5));                       // data loss at now + 15 min
        snoozed(AlarmType.LOW, now.plus(Duration.ofMinutes(12)));           // cleared (AL-5a): not counted
        snoozed(AlarmType.HIGH, now.plus(Duration.ofMinutes(8)));
        snoozed(AlarmType.NO_DATA, now.minus(Duration.ofMinutes(1)));       // expired: ignored
        assertEquals(now.plus(Duration.ofMinutes(8)), evaluate().nextEvaluation());

        setUp();
        latest = reading(120, Duration.ofMinutes(5));
        snoozed(AlarmType.HIGH, clock.now().plus(Duration.ofMinutes(40)));
        assertEquals(latest.time().plus(Duration.ofMinutes(20)), evaluate().nextEvaluation());
    }

    @Test
    public void f12_firstUse_nextEvaluationIsAnchorPlusThreshold() {
        monitoringSince = clock.now().minus(Duration.ofMinutes(5));
        latest = null;
        assertEquals(monitoringSince.plus(Duration.ofMinutes(20)), evaluate().nextEvaluation());
    }

    @Test
    public void f12_nextEvaluation_honoursNoDataSetting() {
        settings = TestSettings.defaults().noDataMinutes(45).build();
        latest = reading(120, Duration.ofMinutes(5));
        assertEquals(latest.time().plus(Duration.ofMinutes(45)), evaluate().nextEvaluation());
    }

    @Test
    public void f12_ongoingDataLoss_noPastInstant() {
        latest = reading(120, Duration.ofMinutes(25));
        assertNull(evaluate().nextEvaluation());
    }

    // ---- R-61 first use ----

    @Test
    public void r61_firstUse_noDataRingsAfterThresholdFromMonitoringSince() {
        latest = null;
        monitoringSince = clock.now().minus(Duration.ofMinutes(19));
        assertNone(evaluate());
        monitoringSince = clock.now().minus(Duration.ofMinutes(20));
        AlarmCommand.Start start = onlyStart(evaluate());
        assertEquals(AlarmType.NO_DATA, start.type());
        assertEquals(monitoringSince, start.view().dataLossSince());
    }

    // ---- M-9 / M-10 ----

    @Test
    public void m9_futureDatedHighValue_notLatest_noAlarm_notUsedForDataLoss() {
        Reading future = new Reading("f1", clock.now().plus(Duration.ofMinutes(12)), 150, Trend.FLAT, "test");
        assertTrue(ReadingRules.isFutureDated(future, clock.now()));
        assertTrue(ReadingRules.latest(List.of(future), clock.now()).isEmpty());

        latest = ReadingRules.latest(List.of(future), clock.now()).orElse(null);
        monitoringSince = null;
        futureDatedLow = future;   // defensive: even if passed, sgv 150 > low never fires
        assertNone(evaluate());

        DataLossStatus dataLoss = classifier.classify(clock.now(), null, null, FetchOutcome.OK,
                settings.dataLossThreshold());
        assertEquals(app.lightscout.domain.dataloss.DataLossKind.FIRST_USE, dataLoss.kind());
    }

    @Test
    public void m9_futureDatedLow_evaluatedAsAgeZero_ringsLow() {
        futureDatedLow = new Reading("f2", clock.now().plus(Duration.ofMinutes(12)), 60, Trend.SINGLE_DOWN, "t");
        fresh(150);
        AlarmCommand.Start start = onlyStart(evaluate());
        assertEquals(AlarmType.LOW, start.type());
        assertEquals(Integer.valueOf(60), start.view().sgv());
        assertEquals(Trend.SINGLE_DOWN, start.view().trend());
    }

    @Test
    public void m9_futureDatedUrgentLow_withoutLatest_ringsUrgentLow() {
        futureDatedLow = new Reading("f3", clock.now().plus(Duration.ofMinutes(30)), 45, Trend.FLAT, "t");
        latest = null;
        assertEquals(AlarmType.URGENT_LOW, startedType(evaluate()));
    }

    @Test
    public void m10_sgv1_isUrgentLow() {
        fresh(1);
        assertEquals(AlarmType.URGENT_LOW, startedType(evaluate()));
    }

    @Test
    public void nothingHolds_noCommands() {
        fresh(120);
        AlarmDecision d = evaluate();
        assertNone(d);
        assertEquals(latest.time().plus(Duration.ofMinutes(20)), d.nextEvaluation());
    }
}
