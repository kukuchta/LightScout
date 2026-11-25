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
import static org.junit.Assert.assertTrue;

/** Architect ruling on SS-05 A-10: an IF-14 write failure never loses a snooze nor skips the AL-6 / F20 re-check. */
public class SnoozeStorageFailureTest {

    private static AlarmState ringing(AlarmType t) {
        return new AlarmState(t, Map.of(t, new AlarmTypeState(NOW.minus(Duration.ofMinutes(2)), null, 0, null)), null);
    }

    @Test
    public void putFails_snoozedTypeDoesNotRingAgain_onReevaluationOrNextTick() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));   // Low holds
        h.alarmState.value = ringing(AlarmType.LOW);
        h.alarmState.failPut = true;
        h.actions.snooze(AlarmType.LOW);
        h.monitorExecutor.runAll();
        assertEquals(List.of(new AlarmCommand.Stop(AlarmType.LOW)), h.alarmController.applied.get(0));
        assertEquals(2, h.alarmController.applied.size());            // re-evaluation still ran
        assertTrue(h.alarmController.applied.get(1).isEmpty());       // snoozed (in memory) → no ring
        h.clock.advanceMinutes(1);
        h.tick();
        assertTrue(h.alarmController.last().isEmpty());
        // the store recovers → the in-memory (dirty) state is persisted
        h.alarmState.failPut = false;
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(NOW.plus(Duration.ofMinutes(15)),
                h.alarmState.value.perType().get(AlarmType.LOW).snoozedUntil());
    }

    @Test
    public void putFails_pendingLowerTypeStillRingsRightAway() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Instant stale = NOW.minus(Duration.ofMinutes(40));
        h.monitorState.value = new MonitorState(s.monitoringSince(), stale, stale, FetchOutcome.OK, null,
                NOW.minusSeconds(60), 60, List.of(), s.lastDailyMaintenance(), s.cursors());
        h.readings.put(reading("r", NOW.minusSeconds(60), 300));
        h.alarmState.value = ringing(AlarmType.NOT_RUNNING);
        h.alarmState.failPut = true;
        h.actions.snooze(AlarmType.NOT_RUNNING);
        h.monitorExecutor.runAll();
        AlarmCommand next = h.alarmController.applied.get(1).get(0);
        assertTrue(next instanceof AlarmCommand.Start);
        assertEquals(AlarmType.HIGH, ((AlarmCommand.Start) next).type());
    }
}
