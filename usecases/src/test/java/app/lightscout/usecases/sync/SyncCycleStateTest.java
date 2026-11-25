package app.lightscout.usecases.sync;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.SyncResult;
import app.lightscout.usecases.common.SettingsEditor;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.page;
import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Fakes.treatment;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** STORAGE, monitor state (step 6), watchdog R-68 / Q-26, alarm wiring, D-022, R-35 resume (CP-02 §9). */
public class SyncCycleStateTest {

    @Test
    public void storage_storeThrows_storageError_lastSuccessfulUnchanged_lastCheckCompletedWritten() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> page(true, List.of(reading("a", NOW.minusSeconds(30), 120)));
        h.readings.fail = true;
        Instant before = h.state().lastSuccessfulCheck();
        SyncResult r = h.tick();   // returns normally
        assertEquals(FetchOutcome.STORAGE_ERROR, r.outcome());
        assertEquals(before, h.state().lastSuccessfulCheck());
        assertEquals(NOW, h.state().lastCheckCompleted());
        assertEquals(FetchOutcome.STORAGE_ERROR, h.state().lastOutcome());
        // data-loss detection still uses the fetched reading (not stored)
        assertEquals(NOW.minusSeconds(30), h.state().lastReadingTime());
        assertTrue(h.logger.events.contains("SYNC_FAIL:STORAGE_ERROR:STORAGE"));
    }

    @Test
    public void monitorState_okTick_lastSuccessfulCheckIsNow() {
        Harness h = new Harness();
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.OK, r.outcome());
        assertEquals(NOW, h.state().lastSuccessfulCheck());
        assertEquals(NOW, h.state().lastCheckCompleted());
        assertEquals(FetchOutcome.OK, h.state().lastOutcome());
        assertEquals(1, h.monitorState.puts.size());
        assertTrue(h.logger.events.contains("SYNC_OK"));
    }

    @Test
    public void debugTiming_onePhaseLinePerTick_durationsOnly() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> page(true, List.of(reading("a", NOW.minusSeconds(30), 123)));
        h.tick();
        assertEquals(1, h.logger.debug.size());
        String line = h.logger.debug.get(0);
        // SR-07: names and durations only — no glucose value, time stamp, identifier or URL.
        assertTrue(line, line.matches("SyncTiming tick read=\\d+ms lastModified=\\d+ms fetch=\\d+ms "
                + "evaluate=\\d+ms publish=\\d+ms persist=\\d+ms maintenance=\\d+ms total=\\d+ms"));
        assertFalse(line, line.contains("123"));
    }

    @Test
    public void monitorState_failingTick_onlyLastCheckCompletedAndOutcomeWritten() {
        Harness h = new Harness();
        MonitorState before = h.state();
        h.gateway.lastModified = () -> Result.fail(FetchOutcome.NO_INTERNET, "NO_NETWORK");
        h.tick();
        MonitorState after = h.state();
        assertEquals(before.lastSuccessfulCheck(), after.lastSuccessfulCheck());
        assertEquals(NOW, after.lastCheckCompleted());
        assertEquals(FetchOutcome.NO_INTERNET, after.lastOutcome());
        assertEquals(before.lastReadingTime(), after.lastReadingTime());
        assertEquals(before.cursors(), after.cursors());
        assertEquals(before.monitoringSince(), after.monitoringSince());
    }

    @Test
    public void monitorStateGetFails_tickCompletes_noPutWithoutGet() {
        Harness h = new Harness();
        h.monitorState.failGet = true;
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.STORAGE_ERROR, r.outcome());
        assertTrue(h.monitorState.puts.isEmpty());
    }

    @Test
    public void r68_watchdog_staleLastCheckCompletedReadBeforeTick_ringsNotRunning() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Instant staleCheck = NOW.minus(Duration.ofMinutes(45));
        h.monitorState.value = new MonitorState(s.monitoringSince(), staleCheck, staleCheck, FetchOutcome.OK, null,
                NOW.minusSeconds(120), 60, List.of(), s.lastDailyMaintenance(), s.cursors());
        h.readings.put(reading("r", NOW.minusSeconds(120), 120));
        h.tick();
        AlarmCommand.Start start = (AlarmCommand.Start) h.alarmController.last().get(0);
        assertEquals(AlarmType.NOT_RUNNING, start.type());
        assertEquals(staleCheck, start.view().lastCheck());
        assertEquals(AlarmType.NOT_RUNNING, h.alarmState.value.activeType());
    }

    @Test
    public void r68_watchdog_nullCheckUsesMonitoringSince_bothNullNeverRings() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        h.monitorState.value = new MonitorState(NOW.minus(Duration.ofMinutes(30)), null, null, FetchOutcome.OK, null,
                NOW.minusSeconds(120), 60, List.of(), s.lastDailyMaintenance(), s.cursors());
        h.readings.put(reading("r", NOW.minusSeconds(120), 120));
        h.tick();
        assertEquals(AlarmType.NOT_RUNNING, ((AlarmCommand.Start) h.alarmController.last().get(0)).type());

        Harness h2 = new Harness();
        h2.monitorState.value = MonitorState.initial();
        h2.tick();
        assertTrue(h2.alarmController.all().isEmpty());
    }

    /** A stale anchor (45 min), the system started {@code bootAgoSec} before this tick. */
    private static Harness staleHarness(long bootAgoSec, Instant anchor) {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        h.monitorState.value = new MonitorState(s.monitoringSince(), anchor, anchor, FetchOutcome.OK, null,
                NOW.minusSeconds(120), 60, List.of(), s.lastDailyMaintenance(), s.cursors());
        h.readings.put(reading("r", NOW.minusSeconds(120), 120));
        h.clock.elapsedSinceBootMs = bootAgoSec * 1000;
        return h;
    }

    @Test
    public void dcr36_firstTickAfterBoot_gapExplainedByPowerOff_noNotRunning() {
        Harness h = staleHarness(30, NOW.minus(Duration.ofMinutes(60)));   // off for ~1 h, booted 30 s ago
        h.tick(app.lightscout.domain.status.TickReason.BOOT);
        assertTrue(h.alarmController.all().isEmpty());
        assertNull(h.alarmState.value.activeType());
        assertTrue(h.logger.events.contains("WATCHDOG_SKIPPED_BOOT"));
        // the tick itself completed: the anchor is fresh again
        assertEquals(NOW, h.state().lastCheckCompleted());
    }

    private static final app.lightscout.domain.status.TickReason[] REASONS = {
            app.lightscout.domain.status.TickReason.BOOT, app.lightscout.domain.status.TickReason.SCHEDULED,
            app.lightscout.domain.status.TickReason.WATCHDOG, app.lightscout.domain.status.TickReason.SERVICE_RESTART,
            app.lightscout.domain.status.TickReason.APP_OPEN};

    @Test
    public void dcr36_anyTickInTheBootWindow_skipsNotRunning() {
        // the first evaluation after a start is not necessarily the BOOT tick (R2 review)
        for (app.lightscout.domain.status.TickReason reason : REASONS) {
            Harness h = staleHarness(30, NOW.minus(Duration.ofMinutes(60)));
            h.tick(reason);
            assertTrue(reason.name(), h.alarmController.all().isEmpty());
            assertTrue(reason.name(), h.logger.events.contains("WATCHDOG_SKIPPED_BOOT"));
        }
    }

    @Test
    public void dcr36_reevaluateInTheBootWindow_skipsAndTheBootTickAfterItStillDoes() {
        Harness h = staleHarness(30, NOW.minus(Duration.ofMinutes(60)));
        h.cycle.reevaluate();   // heartbeat / snooze / IF-62 before any tick: no check completed, anchor unchanged
        assertTrue(h.alarmController.all().isEmpty());
        h.tick(app.lightscout.domain.status.TickReason.BOOT);
        assertTrue(h.alarmController.all().isEmpty());
        assertEquals(NOW, h.state().lastCheckCompleted());
    }

    @Test
    public void dcr36_afterTheBootWindow_theSameGapRingsOnEveryPath() {
        // 11 min after the start nothing completed: the loop is not running, whatever the reason
        for (app.lightscout.domain.status.TickReason reason : REASONS) {
            Harness h = staleHarness(11 * 60, NOW.minus(Duration.ofMinutes(60)));
            h.tick(reason);
            assertEquals(reason.name(), AlarmType.NOT_RUNNING,
                    ((AlarmCommand.Start) h.alarmController.last().get(0)).type());
            assertFalse(h.logger.events.contains("WATCHDOG_SKIPPED_BOOT"));
        }
        Harness h = staleHarness(11 * 60, NOW.minus(Duration.ofMinutes(60)));
        h.cycle.reevaluate();
        assertEquals(AlarmType.NOT_RUNNING, ((AlarmCommand.Start) h.alarmController.last().get(0)).type());
    }

    @Test
    public void dcr36_longAfterTheBoot_aLoopThatStoppedStillRings() {
        Harness h = staleHarness(3 * 3600, NOW.minus(Duration.ofMinutes(45)));   // booted 3 h ago, last check 45 min ago
        h.tick(app.lightscout.domain.status.TickReason.BOOT);
        assertEquals(AlarmType.NOT_RUNNING, ((AlarmCommand.Start) h.alarmController.last().get(0)).type());
        assertFalse(h.logger.events.contains("WATCHDOG_SKIPPED_BOOT"));
    }

    @Test
    public void dcr36_ringingNotRunningRestoredAfterTheStart_keepsRingingUntilSnooze() {
        // persisted ENT-08 from before the shutdown (AL-9, AL-4): the skip must not end an alarm that already rings
        Harness h = staleHarness(30, NOW.minus(Duration.ofMinutes(60)));
        h.alarmState.value = new AlarmState(AlarmType.NOT_RUNNING,
                Map.of(AlarmType.NOT_RUNNING, new AlarmTypeState(NOW.minus(Duration.ofMinutes(50)), null, 0, null)),
                null);
        h.tick(app.lightscout.domain.status.TickReason.BOOT);
        assertEquals(AlarmType.NOT_RUNNING, ((AlarmCommand.Start) h.alarmController.last().get(0)).type());
        assertEquals(AlarmType.NOT_RUNNING, h.alarmState.value.activeType());
    }

    @Test
    public void dcr36_bootTickWithAFreshAnchor_logsNothing() {
        Harness h = staleHarness(30, NOW.minusSeconds(60));
        h.tick(app.lightscout.domain.status.TickReason.BOOT);
        assertFalse(h.logger.events.contains("WATCHDOG_SKIPPED_BOOT"));
    }

    @Test
    public void dcr36_skipDoesNotHideDataLoss_noDataStillRings() {
        // the phone was off, and Nightscout has nothing newer than 45 min: No data rings, NOT_RUNNING does not
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Instant anchor = NOW.minus(Duration.ofMinutes(60));
        h.monitorState.value = new MonitorState(s.monitoringSince(), anchor, anchor, FetchOutcome.OK, null,
                NOW.minus(Duration.ofMinutes(45)), 60, List.of(), s.lastDailyMaintenance(), s.cursors());
        h.readings.put(reading("r", NOW.minus(Duration.ofMinutes(45)), 120));
        h.clock.elapsedSinceBootMs = 30_000L;
        h.tick(app.lightscout.domain.status.TickReason.BOOT);
        assertEquals(AlarmType.NO_DATA, ((AlarmCommand.Start) h.alarmController.last().get(0)).type());
    }

    @Test
    public void alarmWiring_freshSgv60_startLow_statePersistedBeforeApply() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));
        AlarmState[] atApply = new AlarmState[1];
        h.alarmController.onApply = c -> atApply[0] = h.alarmState.value;
        SyncResult r = h.tick();
        AlarmCommand.Start start = (AlarmCommand.Start) h.alarmController.last().get(0);
        assertEquals(AlarmType.LOW, start.type());
        assertEquals(AlarmType.LOW, atApply[0].activeType());   // AL-9: persisted before apply
        assertEquals(NOW, atApply[0].perType().get(AlarmType.LOW).ringingSince());
        assertTrue(r.ringing());
        assertEquals(AlarmType.LOW, h.status.current().ringing());
    }

    @Test
    public void alarmWiring_futureDatedSgv60_stillStartLow() {
        Harness h = new Harness();
        h.readings.put(reading("old", NOW.minus(Duration.ofMinutes(3)), 120),
                reading("future", NOW.plus(Duration.ofMinutes(30)), 60));
        h.tick();
        AlarmCommand.Start start = (AlarmCommand.Start) h.alarmController.last().get(0);
        assertEquals(AlarmType.LOW, start.type());
        assertEquals(Integer.valueOf(60), start.view().sgv());
        assertEquals("old", h.status.current().latest().id());   // excluded from latest
    }

    @Test
    public void applyCalledOncePerEvaluation_evenWhenEmpty() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 120));
        h.tick();
        assertEquals(1, h.alarmController.applied.size());
        assertTrue(h.alarmController.last().isEmpty());
    }

    @Test
    public void d022_alarmsOff_noCommand_remindersAndPumpAlertsContinue_suffixFlag() {
        Harness h = new Harness();
        h.settings.value = SettingsEditor.withAlarmsEnabled(h.settings.value, false);
        h.readings.put(reading("r", NOW.minusSeconds(60), 50));   // urgent low
        h.entries.put(treatment("site", NOW.minus(Duration.ofDays(2)).minus(Duration.ofHours(1)), EntryType.SITE_CHANGE),
                treatment("pa", NOW.minus(Duration.ofMinutes(10)), EntryType.PUMP_ALERT));
        SyncResult r = h.tick();
        assertTrue(h.alarmController.all().isEmpty());
        assertFalse(r.ringing());
        assertEquals(1, h.reminders.posted.size());
        assertEquals(1, h.pumpAlerts.posted.size());
        assertFalse(h.status.current().alarmsEnabled());
        assertEquals("s10_text_alarms_off_suffix",
                app.lightscout.usecases.status.SurfaceKeys.of(h.status.current()).s10Suffix());
        assertEquals(FetchOutcome.OK, h.state().lastOutcome());   // monitoring continues
    }

    @Test
    public void r35_resume_firstEvaluationInProcessWithActiveType_emitsStart() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 120));   // condition no longer holds
        h.alarmState.value = new AlarmState(AlarmType.HIGH,
                Map.of(AlarmType.HIGH, new AlarmTypeState(NOW.minus(Duration.ofMinutes(5)), null, 2, null)), 7);
        h.tick();
        AlarmCommand first = h.alarmController.last().get(0);
        assertTrue(first instanceof AlarmCommand.Start);
        assertEquals(AlarmType.HIGH, ((AlarmCommand.Start) first).type());
        assertEquals(NOW.minus(Duration.ofMinutes(5)), h.alarmState.value.perType().get(AlarmType.HIGH).ringingSince());
        assertEquals(2, h.alarmState.value.perType().get(AlarmType.HIGH).escalationStep());
        assertEquals(Integer.valueOf(7), h.alarmState.value.savedUserAlarmVolume());
        h.clock.advanceMinutes(1);
        h.tick();   // later evaluations: keep-alive Update (AL-4)
        assertTrue(h.alarmController.last().get(0) instanceof AlarmCommand.Update);
    }

    @Test
    public void syncResult_carriesSchedulingInputs() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(180), 120));
        SyncResult r = h.tick();
        assertEquals(NOW.minusSeconds(180), r.lastReadingTime());
        assertEquals(NOW.minusSeconds(180).plus(Duration.ofMinutes(20)), r.nextEvaluation());
        assertEquals(20, r.noDataMinutes());
        assertEquals(60, r.arrivalLagMedianSec());
        assertFalse(r.newReading());
    }

    @Test
    public void reevaluate_noNetwork_projectsAndEvaluates() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));
        SyncResult r = h.cycle.reevaluate();
        assertTrue(h.gateway.calls.isEmpty());
        assertTrue(r.ringing());
        assertTrue(h.monitorState.puts.isEmpty());
        assertNotNull(h.status.current());
        assertEquals(FetchOutcome.OK, r.outcome());
    }

    @Test
    public void firstUse_noReadingEver_dataLossAfterThreshold_rings() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        h.monitorState.value = new MonitorState(NOW.minus(Duration.ofMinutes(25)), NOW.minusSeconds(60),
                NOW.minusSeconds(60), FetchOutcome.OK, null, null, 60, List.of(), s.lastDailyMaintenance(),
                s.cursors());
        h.tick();
        assertEquals(AlarmType.NO_DATA, ((AlarmCommand.Start) h.alarmController.last().get(0)).type());
        assertNull(h.status.current().latest());
    }
}
