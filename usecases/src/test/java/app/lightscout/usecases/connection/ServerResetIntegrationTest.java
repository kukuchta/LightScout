package app.lightscout.usecases.connection;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.reading;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * CS-5 / DS:A-23 / R-71 with the real CP-02a cycle: after the reset task the next tick starts again from the SY-8
 * initial fill with a fresh {@code monitoringSince}, and the shared memories no longer carry the old server's state
 * (SS-05 IR-38).
 */
public class ServerResetIntegrationTest {

    @Test
    public void resetThenTick_restartsWithSy8_initialFill_andFreshMonitoringSince() {
        Harness h = new Harness();
        h.readings.put(reading("old", Harness.NOW.minusSeconds(180), 120));
        h.tick();   // steady tick on the old server: memories now hold the old server's state
        ConnectionStateReset reset = new ConnectionStateReset(new app.lightscout.usecases.fakes.Cp02bFakes.FakeGateway(),
                new OriginBoundTokenSource(new app.lightscout.usecases.fakes.Cp02bFakes.FakeConnectionStore()),
                new app.lightscout.usecases.common.ServerEpoch(), h.readings, h.entries, h.deviceStatus, h.monitorState,
                h.alarmState, h.reminderState, h.notified, h.reminders, h.alarmMemory, h.monitorMemory,
                h.monitorControl);
        h.clock.advanceMinutes(1);
        h.monitorExecutor.execute(() -> reset.resetServer("https://ns.example.org"));
        h.monitorExecutor.runAll();
        assertEquals(List.of(TickReason.RECONFIGURED), h.monitorControl.ticks);
        assertTrue(h.readings.rows.isEmpty());
        h.gateway.readingsSinceCalls.clear();
        h.tick();
        // SY-8: the last 24 h first (no lastReadingTime any more)
        assertEquals(List.of(h.clock.now.minus(Duration.ofHours(24)).toEpochMilli()), h.gateway.readingsSinceCalls);
        assertEquals(h.clock.now, h.state().monitoringSince());
    }

    @Test
    public void resetWhileRinging_alarmKeepsRinging_noStopCommand() {
        Harness h = new Harness();
        h.readings.put(reading("low", Harness.NOW.minusSeconds(60), 60));
        h.alarmState.value = new AlarmState(AlarmType.LOW, Map.of(AlarmType.LOW,
                new AlarmTypeState(Harness.NOW.minus(Duration.ofMinutes(3)), null, 0, null)), null);
        ConnectionStateReset reset = new ConnectionStateReset(new app.lightscout.usecases.fakes.Cp02bFakes.FakeGateway(),
                new OriginBoundTokenSource(new app.lightscout.usecases.fakes.Cp02bFakes.FakeConnectionStore()),
                new app.lightscout.usecases.common.ServerEpoch(), h.readings, h.entries, h.deviceStatus, h.monitorState,
                h.alarmState, h.reminderState, h.notified, h.reminders, h.alarmMemory, h.monitorMemory,
                h.monitorControl);
        h.monitorExecutor.execute(() -> reset.resetServer("https://ns.example.org"));
        h.monitorExecutor.runAll();
        assertEquals(AlarmType.LOW, h.alarmState.value.activeType());
        assertTrue(h.alarmController.applied.isEmpty());   // the reset itself sends no IF-16 command (AL-4)
    }
}
