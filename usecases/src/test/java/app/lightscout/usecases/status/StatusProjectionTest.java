package app.lightscout.usecases.status;

import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.common.SettingsEditor;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** §8.3 snapshot fields, banner priority, outcome → surface, ADR-025, refresh / retry (CP-02 §9). */
public class StatusProjectionTest {

    private static final DataLossStatus NONE = new DataLossStatus(DataLossKind.NONE, null, null);

    @Test
    public void bannerPriority_alarmsOffWinsOverReadinessAndConnection() {
        assertEquals(Banner.ALARMS_OFF, StatusProjector.bannerTop(false, true, true, 2,
                FetchOutcome.NS_NOT_RESPONDING, null, NONE, true, null));
    }

    @Test
    public void bannerPriority_lowOffAndOffline_singleAlarmOff() {
        assertEquals(Banner.SINGLE_ALARM_OFF, StatusProjector.bannerTop(true, false, true, 0,
                FetchOutcome.NO_INTERNET, null, NONE, false, reading("r", NOW, 100)));
    }

    @Test
    public void bannerPriority_onlyOffline_offline() {
        assertEquals(Banner.OFFLINE, StatusProjector.bannerTop(true, true, true, 0,
                FetchOutcome.NO_INTERNET, null, NONE, false, reading("r", NOW, 100)));
        assertEquals(Banner.READINESS, StatusProjector.bannerTop(true, true, true, 1,
                FetchOutcome.NS_NOT_RESPONDING, null, NONE, true, null));
        assertEquals(Banner.NONE, StatusProjector.bannerTop(true, true, true, 0, FetchOutcome.STORAGE_ERROR, null,
                NONE, true, reading("r", NOW, 100)));
    }

    /** One row of the §8.3 outcome → surface table (with R-76 / R-81 applied). */
    private record Row(FetchOutcome outcome, FetchOutcome sticky, boolean dataLoss, String banner, String action,
                       String s10, String cause) {
    }

    @Test
    public void outcomeToSurface_everyRowOfTheTable() {
        Instant fresh = NOW.minusSeconds(60);
        Instant stale = NOW.minus(Duration.ofMinutes(25));
        List<Row> rows = List.of(
                new Row(FetchOutcome.OK, null, false, null, null, "s10_text", null),
                new Row(FetchOutcome.OK, null, true, "s01_error_data_loss", "s01_banner_action_retry",
                        "s10_text_data_loss", "s09_cause_uploader"),
                new Row(FetchOutcome.NO_INTERNET, null, true, "s01_offline", null, "s10_offline",
                        "s09_cause_no_internet"),
                new Row(FetchOutcome.NS_NOT_RESPONDING, null, true, "s01_error_ns_not_responding",
                        "s01_banner_action_retry", "s10_error_ns_not_responding", "s09_cause_ns_not_responding"),
                new Row(FetchOutcome.OK, FetchOutcome.NOT_NIGHTSCOUT, false, "s01_error_not_nightscout",
                        "s01_banner_action_fix_connection", "s10_error_ns_not_responding", null),
                new Row(FetchOutcome.TOKEN_REJECTED, null, true, "s01_error_token_rejected",
                        "s01_banner_action_fix_connection", "s10_error_token_rejected", "s09_cause_ns_not_responding"),
                new Row(FetchOutcome.OK, FetchOutcome.PERMISSION_MISSING, false, "s01_error_token_permission_read",
                        "s01_banner_action_fix_connection", "s10_error_token_rejected", null),
                new Row(FetchOutcome.OK, FetchOutcome.PERMISSION_WRITE_MISSING, false,
                        "s01_error_token_permission_write", "s01_banner_action_fix_connection", "s10_text", null),
                new Row(FetchOutcome.OK, FetchOutcome.SERVER_TOO_OLD, false, "s01_error_version_too_old",
                        "s01_banner_action_fix_connection", "s10_error_ns_not_responding", null),
                new Row(FetchOutcome.STORAGE_ERROR, null, false, null, null, "s10_text", null));
        for (Row row : rows) {
            Harness h = new Harness();
            h.connectivity.online = row.outcome() != FetchOutcome.NO_INTERNET;
            Instant last = row.dataLoss() ? stale : fresh;
            h.readings.put(reading("r", last, 120));
            MonitorState ms = state(row.outcome(), row.sticky(), last);
            DataLossStatus dl = new app.lightscout.domain.dataloss.DataLossClassifier().classify(NOW, last,
                    ms.monitoringSince(), row.outcome(), Duration.ofMinutes(20));
            StatusSnapshot s = h.projector.project(NOW, ms, Settings.defaults(),
                    app.lightscout.domain.alarm.AlarmState.empty(), reading("r", last, 120), dl, false);
            SurfaceKeys.SurfaceCodes codes = SurfaceKeys.of(s);
            String label = row.toString();
            assertEquals(label, row.banner(), codes.s01Banner());
            assertEquals(label, row.action(), codes.s01Action());
            assertEquals(label, row.s10(), codes.s10Text());
            assertEquals(label, row.cause(), codes.causeLine());
        }
    }

    @Test
    public void snapshotCarriesTheCycleBetweenTheLastAndTheNextPlannedCheck() {
        Harness h = new Harness();
        Instant last = NOW.minusSeconds(60);   // the reading arrived a minute ago, the check just ended
        MonitorState ms = state(FetchOutcome.OK, null, last);
        DataLossStatus dl = new DataLossStatus(DataLossKind.NONE, null, null);
        StatusSnapshot s = h.projector.project(NOW, ms, Settings.defaults(),
                app.lightscout.domain.alarm.AlarmState.empty(), reading("b", last, 140), dl, false);
        app.lightscout.domain.status.CheckCycle cycle = s.checkCycle();
        assertEquals(NOW, cycle.start());
        // the first window tick: reading + 5 min + lag (60 s) + 5 s
        assertEquals(last.plusSeconds(5 * 60 + 60 + 5), cycle.next());
        assertEquals(app.lightscout.domain.status.CheckCycle.Kind.NORMAL, cycle.kind());
        MonitorState failed = state(FetchOutcome.NO_INTERNET, null, last);
        assertEquals(app.lightscout.domain.status.CheckCycle.Kind.MISSED, h.projector.project(NOW, failed,
                Settings.defaults(), app.lightscout.domain.alarm.AlarmState.empty(), reading("b", last, 140), dl,
                false).checkCycle().kind());
    }

    @Test
    public void deltaIsTheChangeToTheReadingFiveMinutesEarlier() {
        Harness h = new Harness();
        Instant last = NOW.minusSeconds(60);
        h.readings.put(reading("a", last.minusSeconds(300), 131));
        h.readings.put(reading("b", last, 140));
        MonitorState ms = state(FetchOutcome.OK, null, last);
        DataLossStatus dl = new DataLossStatus(DataLossKind.NONE, null, null);
        StatusSnapshot s = h.projector.project(NOW, ms, Settings.defaults(),
                app.lightscout.domain.alarm.AlarmState.empty(), reading("b", last, 140), dl, false);
        assertEquals(Integer.valueOf(9), s.deltaMgdl());
        // no predecessor in the 4-6 minute window: no delta
        Harness lone = new Harness();
        lone.readings.put(reading("b", last, 140));
        StatusSnapshot none = lone.projector.project(NOW, ms, Settings.defaults(),
                app.lightscout.domain.alarm.AlarmState.empty(), reading("b", last, 140), dl, false);
        assertNull(none.deltaMgdl());
    }

    @Test
    public void otherBannersAndFirstUse() {
        Harness h = new Harness();
        Settings s = SettingsEditor.withLowEnabled(SettingsEditor.withHighEnabled(Settings.defaults(), false), false);
        StatusSnapshot snap = h.projector.project(NOW, state(FetchOutcome.OK, null, NOW), s,
                app.lightscout.domain.alarm.AlarmState.empty(), null,
                new DataLossStatus(DataLossKind.FIRST_USE, null, NOW), false);
        assertEquals("s01_low_high_alarms_off", SurfaceKeys.of(snap).s01Banner());
        assertEquals("s01_banner_action_turn_on", SurfaceKeys.of(snap).s01Action());
        assertEquals("s10_text_first_use", SurfaceKeys.of(snap).s10Text());
        h.readiness.missing = 1;
        StatusSnapshot r = h.projector.project(NOW, state(FetchOutcome.OK, null, NOW), Settings.defaults(),
                app.lightscout.domain.alarm.AlarmState.empty(), null, NONE, false);
        assertEquals("s01_permission_missing", SurfaceKeys.of(r).s01Banner());
        assertEquals("s01_banner_action_fix", SurfaceKeys.of(r).s01Action());
        assertEquals(1, r.readinessMissingRequired());
        assertEquals("s09_cause_no_internet", SurfaceKeys.causeLine(DataLossCause.NO_INTERNET));
    }

    @Test
    public void faultsListEveryActiveChipWithoutPriorityHiding() {
        Harness h = new Harness();
        h.readiness.missing = 1;
        Settings s = SettingsEditor.withLowEnabled(Settings.defaults(), false);
        StatusSnapshot snap = h.projector.project(NOW, state(FetchOutcome.TOKEN_REJECTED, null, NOW), s,
                app.lightscout.domain.alarm.AlarmState.empty(), reading("r", NOW.minusSeconds(60), 120), NONE, false);
        List<SurfaceKeys.Fault> faults = SurfaceKeys.faults(snap);
        assertEquals(List.of("s01_low_alarm_off", "s01_permission_missing", "s01_error_token_rejected"),
                faults.stream().map(SurfaceKeys.Fault::banner).toList());
        assertEquals(SurfaceKeys.of(snap).s01Banner(), faults.get(0).banner());
        assertEquals("s01_banner_action_fix_connection", faults.get(2).action());
        assertTrue(SurfaceKeys.faults(h.projector.project(NOW, state(FetchOutcome.OK, null, NOW),
                Settings.defaults(), app.lightscout.domain.alarm.AlarmState.empty(), null, NONE, false)).stream()
                .noneMatch(f -> f.banner().equals("s01_offline")));
    }

    @Test
    public void snoozesProjectOnlyRunningOnes() {
        Harness h = new Harness();
        app.lightscout.domain.alarm.AlarmState as = new app.lightscout.domain.alarm.AlarmState(null, java.util.Map.of(
                app.lightscout.domain.alarm.AlarmType.HIGH, new app.lightscout.domain.alarm.AlarmTypeState(
                        null, NOW.plusSeconds(600), 0, null),
                app.lightscout.domain.alarm.AlarmType.LOW, new app.lightscout.domain.alarm.AlarmTypeState(
                        null, NOW, 0, null)), null);
        StatusSnapshot snap = h.projector.project(NOW, state(FetchOutcome.OK, null, NOW), Settings.defaults(),
                as, null, NONE, false);
        assertEquals(java.util.Map.of(app.lightscout.domain.alarm.AlarmType.HIGH, NOW.plusSeconds(600)),
                snap.snoozes());
        assertEquals(snap.snoozes(), StatusProjector.withFetching(snap, true).snoozes());
    }

    @Test
    public void adr025_sameSnapshotObjectToTwoListenersInOneTick() {
        Harness h = new Harness();
        List<StatusSnapshot> a = new ArrayList<>();
        List<StatusSnapshot> b = new ArrayList<>();
        h.status.addListener(a::add);
        h.status.addListener(b::add);
        h.tick();
        assertEquals(a.size(), b.size());
        StatusSnapshot lastA = a.get(a.size() - 1);
        assertSame(lastA, b.get(b.size() - 1));
        assertSame(lastA, h.status.current());
        assertFalse(lastA.fetching());
        assertTrue("fetching flag published at tick start", a.get(0).fetching());
    }

    @Test
    public void snapshotFields_futureExcluded_nextExpected_overdue() {
        Harness h = new Harness();
        Instant t = NOW.minus(Duration.ofMinutes(4));
        h.readings.put(reading("r", t, 120), reading("future", NOW.plus(Duration.ofMinutes(30)), 130));
        h.tick();
        StatusSnapshot s = h.status.current();
        assertEquals("r", s.latest().id());
        assertEquals(t.plus(Duration.ofMinutes(5)), s.nextExpected());
        assertFalse(s.overdue());
        assertEquals(Duration.ofMinutes(4), s.readingAge());
        h.clock.advanceMinutes(2);
        h.tick();
        assertTrue(h.status.current().overdue());
    }

    @Test
    public void snapshotFields_iob16MinHidden_14MinShown() {
        Harness h = new Harness();
        h.deviceStatus.rows.add(new DeviceStatus("d", NOW.minus(Duration.ofMinutes(16)), 150, "pump"));
        h.tick();
        assertNull(h.status.current().iobCenti());
        h.deviceStatus.rows.add(new DeviceStatus("d2", NOW.minus(Duration.ofMinutes(14)), 120, "pump"));
        h.tick();
        assertEquals(Integer.valueOf(120), h.status.current().iobCenti());
    }

    /** DCR-45: the fill tick shows „Loading…” (no change cached, backfill not done); once complete: „No records”. */
    @Test
    public void consumables_loadingWhileFirstBackfillRuns_thenNoneFound() {
        Harness h = new Harness();
        h.monitorState.value = MonitorState.initial();
        h.gateway.lastModified = () -> Result.ok(Harness.steadyLastModified());
        h.tick();
        StatusSnapshot first = h.status.current();
        assertEquals(app.lightscout.domain.consumable.ConsumableState.LOADING, first.siteStatus().state());
        assertEquals(app.lightscout.domain.consumable.ConsumableState.LOADING, first.sensorStatus().state());
        h.clock.advanceMinutes(1);
        h.tick();   // the backfill of the fill tick reached the cache window
        assertEquals(app.lightscout.domain.consumable.ConsumableState.NONE_FOUND,
                h.status.current().siteStatus().state());
    }

    /** DCR-45: a change entry that is cached is shown at once, also while the backfill still runs. */
    @Test
    public void consumables_cachedChangeIsShownWhileBackfillRuns() {
        Harness h = new Harness();
        h.monitorState.value = MonitorState.initial();
        h.gateway.lastModified = () -> Result.ok(Harness.steadyLastModified());
        // 60 h old: outside the 48 h hot window, so the fill tick's set difference does not delete it; site
        // lifetime 3 d → „Change soon” from 48 h
        h.entries.put(app.lightscout.usecases.fakes.Fakes.treatment("s", NOW.minus(Duration.ofHours(60)),
                app.lightscout.domain.model.EntryType.SITE_CHANGE));
        h.tick();
        StatusSnapshot s = h.status.current();
        assertEquals(app.lightscout.domain.consumable.ConsumableState.CHANGE_SOON, s.siteStatus().state());
        assertEquals(app.lightscout.domain.consumable.ConsumableState.LOADING, s.sensorStatus().state());
    }

    /** DCR-45: „Loading…” is bounded: a backfill that never finishes cannot keep it for longer than 10 min. */
    @Test
    public void consumables_loadingEndsAfterTenMinutesEvenIfBackfillIncomplete() {
        MonitorState steady = Harness.steadyState(NOW);
        java.util.Map<app.lightscout.domain.model.NsCollection, app.lightscout.domain.monitor.CollectionCursor> cursors =
                new java.util.EnumMap<>(steady.cursors());
        cursors.put(app.lightscout.domain.model.NsCollection.TREATMENTS,
                new app.lightscout.domain.monitor.CollectionCursor(Harness.LM_TREATMENTS, null, null,
                        NOW.minus(Duration.ofDays(2)), false, null));
        MonitorState young = new MonitorState(NOW.minus(Duration.ofMinutes(9)), steady.lastSuccessfulCheck(),
                steady.lastCheckCompleted(), steady.lastOutcome(), null, steady.lastReadingTime(), 60, List.of(),
                steady.lastDailyMaintenance(), cursors);
        MonitorState old = new MonitorState(NOW.minus(Duration.ofMinutes(11)), steady.lastSuccessfulCheck(),
                steady.lastCheckCompleted(), steady.lastOutcome(), null, steady.lastReadingTime(), 60, List.of(),
                steady.lastDailyMaintenance(), cursors);
        assertTrue(StatusProjector.historyLoading(young, NOW));
        assertFalse(StatusProjector.historyLoading(old, NOW));
        assertFalse("complete cache: not loading", StatusProjector.historyLoading(steady, NOW));
        assertFalse("no connection yet", StatusProjector.historyLoading(MonitorState.initial(), NOW));
    }

    @Test
    public void snapshotFields_consumablesAndThresholdsAndPostTickCheckTime() {
        Harness h = new Harness();
        h.entries.put(app.lightscout.usecases.fakes.Fakes.treatment("s", NOW.minus(Duration.ofDays(1)),
                app.lightscout.domain.model.EntryType.SENSOR_CHANGE));
        h.tick();
        StatusSnapshot s = h.status.current();
        assertEquals(app.lightscout.domain.consumable.ConsumableState.NORMAL, s.sensorStatus().state());
        assertEquals(app.lightscout.domain.consumable.ConsumableState.NONE_FOUND, s.siteStatus().state());
        assertEquals(70, s.lowMgdl());
        assertEquals(NOW, s.lastSuccessfulCheck());   // R-69 (1): this tick's check time
        assertTrue(s.configured());
    }

    @Test
    public void refresh_runsOnMonitorExecutor_retryRequestsUserRetryTick() {
        Harness h = new Harness();
        int before = h.snapshots.size();
        h.status.refresh();
        assertEquals(before, h.snapshots.size());          // nothing until the monitor executor runs
        h.monitorExecutor.runAll();
        assertEquals(before + 1, h.snapshots.size());
        h.status.retry();
        assertEquals(List.of(TickReason.USER_RETRY), h.monitorControl.ticks);
    }

    @Test
    public void connectivityChange_reprojectsOnline() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 120));
        h.tick();
        h.connectivity.set(false);
        h.monitorExecutor.runAll();
        assertFalse(h.status.current().online());
        assertEquals(Banner.OFFLINE, h.status.current().bannerTop());
    }

    @Test
    public void listenerFailure_doesNotStopOthers() {
        Harness h = new Harness();
        List<StatusSnapshot> got = new ArrayList<>();
        h.status.addListener(s -> {
            throw new IllegalStateException("bad listener");
        });
        h.status.addListener(got::add);
        h.gateway.lastModified = () -> Result.ok(Harness.steadyLastModified());
        h.tick();
        assertFalse(got.isEmpty());
    }

    /** Home shows one chip per slot: the connection / data-loss slot must never get two faults (DCR-21 / DCR-24). */
    @Test
    public void atMostOneConnectionOrDataLossFaultForEveryOutcomeCombination() {
        java.util.Set<String> otherSlots = java.util.Set.of(SurfaceKeys.S01_ALARMS_OFF, SurfaceKeys.S01_LOW_ALARM_OFF,
                SurfaceKeys.S01_HIGH_ALARM_OFF, SurfaceKeys.S01_LOW_HIGH_ALARMS_OFF,
                SurfaceKeys.S01_PERMISSION_MISSING, SurfaceKeys.S01_OFFLINE);
        List<FetchOutcome> sticky = new ArrayList<>(List.of(FetchOutcome.values()));
        sticky.add(0, null);
        for (FetchOutcome outcome : FetchOutcome.values()) {
            for (FetchOutcome problem : sticky) {
                for (boolean stale : new boolean[] {false, true}) {
                    for (boolean online : new boolean[] {false, true}) {
                        Harness h = new Harness();
                        h.connectivity.online = online;
                        Instant last = stale ? NOW.minus(Duration.ofMinutes(25)) : NOW.minusSeconds(60);
                        h.readings.put(reading("r", last, 120));
                        MonitorState ms = state(outcome, problem, last);
                        DataLossStatus dl = new app.lightscout.domain.dataloss.DataLossClassifier().classify(NOW, last,
                                ms.monitoringSince(), outcome, Duration.ofMinutes(20));
                        StatusSnapshot s = h.projector.project(NOW, ms, Settings.defaults(),
                                app.lightscout.domain.alarm.AlarmState.empty(), reading("r", last, 120), dl, false);
                        long connection = SurfaceKeys.faults(s).stream()
                                .filter(f -> !otherSlots.contains(f.banner())).count();
                        assertTrue(outcome + "/" + problem + "/stale=" + stale + "/online=" + online,
                                connection <= 1);
                    }
                }
            }
        }
    }

    private static MonitorState state(FetchOutcome outcome, FetchOutcome sticky, Instant lastReading) {
        MonitorState s = Harness.steadyState(NOW);
        return new MonitorState(s.monitoringSince(), NOW, NOW, outcome, sticky, lastReading, 60, List.of(),
                s.lastDailyMaintenance(), s.cursors());
    }
}
