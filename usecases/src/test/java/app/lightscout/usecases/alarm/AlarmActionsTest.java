package app.lightscout.usecases.alarm;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** AL-5 / F20 snooze, D-022 switches, R-29 no-op types, single writer (CP-02 §9). */
public class AlarmActionsTest {

    private static AlarmState ringing(AlarmType t) {
        return new AlarmState(t, Map.of(t, new AlarmTypeState(NOW.minus(Duration.ofMinutes(2)), null, 1, null)), null);
    }

    @Test
    public void snoozeLow_stopLow_snoozedUntilNowPlus15() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));   // Low still holds
        h.alarmState.value = ringing(AlarmType.LOW);
        h.actions.snooze(AlarmType.LOW);
        h.monitorExecutor.runAll();
        assertEquals(List.of(new AlarmCommand.Stop(AlarmType.LOW)), h.alarmController.applied.get(0));
        AlarmTypeState low = h.alarmState.value.perType().get(AlarmType.LOW);
        assertEquals(NOW.plus(Duration.ofMinutes(15)), low.snoozedUntil());
        assertNull(low.ringingSince());
        assertNull(h.alarmState.value.activeType());
        // the re-evaluation does not ring Low again while snoozed
        assertTrue(h.alarmController.applied.get(1).isEmpty());
        assertTrue(h.logger.events.contains("ALARM_SNOOZE:LOW"));
    }

    @Test
    public void snoozeNotRunning_usesNoDataSnooze30() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Instant stale = NOW.minus(Duration.ofMinutes(40));
        h.monitorState.value = new MonitorState(s.monitoringSince(), stale, stale, FetchOutcome.OK, null,
                NOW.minusSeconds(60), 60, List.of(), s.lastDailyMaintenance(), s.cursors());
        h.alarmState.value = ringing(AlarmType.NOT_RUNNING);
        h.actions.snooze(AlarmType.NOT_RUNNING);
        h.monitorExecutor.runAll();
        assertEquals(NOW.plus(Duration.ofMinutes(30)),
                h.alarmState.value.perType().get(AlarmType.NOT_RUNNING).snoozedUntil());
    }

    @Test
    public void snoozeHigherType_pendingLowerTypeRingsRightAway() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Instant stale = NOW.minus(Duration.ofMinutes(40));
        h.monitorState.value = new MonitorState(s.monitoringSince(), stale, stale, FetchOutcome.OK, null,
                NOW.minusSeconds(60), 60, List.of(), s.lastDailyMaintenance(), s.cursors());
        h.readings.put(reading("r", NOW.minusSeconds(60), 300));    // High holds, lower priority than NOT_RUNNING
        h.alarmState.value = ringing(AlarmType.NOT_RUNNING);
        h.actions.snooze(AlarmType.NOT_RUNNING);
        h.monitorExecutor.runAll();
        assertEquals(new AlarmCommand.Stop(AlarmType.NOT_RUNNING), h.alarmController.applied.get(0).get(0));
        AlarmCommand next = h.alarmController.applied.get(1).get(0);
        assertTrue(next instanceof AlarmCommand.Start);
        assertEquals(AlarmType.HIGH, ((AlarmCommand.Start) next).type());
        assertEquals(AlarmType.HIGH, h.alarmState.value.activeType());
    }

    @Test
    public void singleWriter_allMutationsRunOnTheMonitorExecutor() {
        Harness h = new Harness();
        h.alarmState.requiredExecutor = h.monitorExecutor;
        h.settings.requiredExecutor = h.monitorExecutor;
        h.alarmState.value = ringing(AlarmType.LOW);
        h.actions.snooze(AlarmType.LOW);
        h.actions.setAlarmsEnabled(false);
        h.actions.setAlarmTypeEnabled(AlarmType.HIGH, false);
        assertTrue("nothing happens on the calling thread", h.alarmController.applied.isEmpty());
        assertTrue(h.alarmState.puts.isEmpty());
        assertTrue(h.settings.puts.isEmpty());
        assertEquals(0, h.monitorControl.reevaluations);
        assertEquals(3, h.monitorExecutor.queue.size());
        h.monitorExecutor.runAll();   // the fakes throw if a write happens off the executor
        assertFalse(h.alarmState.puts.isEmpty());
        assertEquals(2, h.settings.puts.size());
        assertEquals(2, h.monitorControl.reevaluations);
    }

    @Test
    public void d022_alarmsOffThenOn_withSgv60_immediateStartLow() {
        Harness h = new Harness();
        h.monitorControl.onReevaluate = h.cycle::reevaluate;
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));
        h.actions.setAlarmsEnabled(false);
        h.monitorExecutor.runAll();
        assertFalse(h.settings.value.alarmsEnabled());
        assertTrue(h.alarmController.all().isEmpty());
        h.actions.setAlarmsEnabled(true);
        h.monitorExecutor.runAll();
        assertTrue(h.settings.value.alarmsEnabled());
        assertEquals(new AlarmCommand.Start(AlarmType.LOW, ((AlarmCommand.Start) h.alarmController.all().get(0)).view()),
                h.alarmController.all().get(0));
    }

    @Test
    public void r60_alarmsOffWhileRinging_stops() {
        Harness h = new Harness();
        h.monitorControl.onReevaluate = h.cycle::reevaluate;
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));
        h.alarmState.value = ringing(AlarmType.LOW);
        h.actions.setAlarmsEnabled(false);
        h.monitorExecutor.runAll();
        assertEquals(new AlarmCommand.Stop(AlarmType.LOW), h.alarmController.all().get(0));
        assertNull(h.alarmState.value.activeType());
        assertNull(h.alarmState.value.perType().getOrDefault(AlarmType.LOW,
                new AlarmTypeState(null, null, 0, null)).snoozedUntil());
    }

    @Test
    public void setAlarmTypeEnabled_lowAndHighOnly_othersNoOp() {
        Harness h = new Harness();
        h.actions.setAlarmTypeEnabled(AlarmType.URGENT_LOW, false);
        h.actions.setAlarmTypeEnabled(AlarmType.NO_DATA, false);
        h.actions.setAlarmTypeEnabled(AlarmType.NOT_RUNNING, false);
        assertTrue(h.monitorExecutor.queue.isEmpty());
        h.actions.setAlarmTypeEnabled(AlarmType.LOW, false);
        h.monitorExecutor.runAll();
        assertFalse(h.settings.value.lowEnabled());
        assertTrue(h.settings.value.highEnabled());
        assertEquals(1, h.monitorControl.reevaluations);
        h.actions.setAlarmTypeEnabled(AlarmType.HIGH, false);
        h.monitorExecutor.runAll();
        assertFalse(h.settings.value.highEnabled());
    }
}
