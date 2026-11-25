package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;

/**
 * WF-01 step 6 refusal path, F14 elapsed realtime, D-020 / D-030 {@code setAlarmClock} scope, heartbeat interval.
 */
public class MonitorAlarmsTest {

    private final Fakes.FakeClock clock = new Fakes.FakeClock();
    private final Fakes.FakeAlarmPort port = new Fakes.FakeAlarmPort();
    private final Fakes.RecordingLogger logger = new Fakes.RecordingLogger();

    private MonitorAlarms alarms(boolean alarmClockFallback) {
        return new MonitorAlarms(port, clock, logger, alarmClockFallback);
    }

    @Test
    public void tick_isExactElapsed_convertedFromWallInstant() {
        long armed = alarms(false).armTick(new TickPlan(clock.now.plusSeconds(90), TickPhase.OVERDUE));
        assertEquals(clock.elapsed + 90_000L, armed);
        assertEquals(Long.valueOf(armed), port.exact.get(AlarmPort.Kind.TICK));
    }

    @Test
    public void f14_wallClockJumpAfterArming_doesNotMoveArmedAlarm() {
        MonitorAlarms a = alarms(false);
        a.armWatchdog(clock.now.plus(Duration.ofMinutes(20)));
        long armed = port.exact.get(AlarmPort.Kind.WATCHDOG);
        clock.jumpWall(Duration.ofHours(3));
        assertEquals(Long.valueOf(armed), port.exact.get(AlarmPort.Kind.WATCHDOG));
    }

    @Test
    public void pastInstant_triggersNow() {
        assertEquals(clock.elapsed, alarms(false).toElapsed(clock.now.minusSeconds(30)));
    }

    @Test
    public void exactNotAllowed_inexactFallback_andExactAlarmDeniedLoggedOnce() {
        port.canExact = false;
        MonitorAlarms a = alarms(false);
        a.armTick(new TickPlan(clock.now.plusSeconds(60), TickPhase.ARRIVAL_WINDOW));
        a.armWatchdog(clock.now.plusSeconds(600));
        assertTrue(port.inexact.containsKey(AlarmPort.Kind.TICK));
        assertTrue(port.inexact.containsKey(AlarmPort.Kind.WATCHDOG));
        assertEquals(1, logger.count("EXACT_ALARM_DENIED"));
    }

    @Test
    public void exactThrowsSecurityException_inexactFallback() {
        port.throwOnExact = true;
        alarms(false).armHeartbeat();
        assertTrue(port.inexact.containsKey(AlarmPort.Kind.HEARTBEAT));
        assertEquals(1, logger.count("EXACT_ALARM_DENIED"));
    }

    @Test
    public void alarmClock_neverUsedOnRecommendedPath() {
        alarms(false).armTick(new TickPlan(clock.now.plusSeconds(60), TickPhase.EXPECTED_READING));
        assertTrue(port.alarmClock.isEmpty());
    }

    @Test
    public void vr07Fallback_alarmClockOnlyForExpectedReadingTick() {
        MonitorAlarms a = alarms(true);
        a.armTick(new TickPlan(clock.now.plusSeconds(60), TickPhase.EXPECTED_READING));
        assertEquals(Long.valueOf(clock.now.plusSeconds(60).toEpochMilli()), port.alarmClock.get(AlarmPort.Kind.TICK));
        for (TickPhase phase : new TickPhase[] {TickPhase.ARRIVAL_WINDOW, TickPhase.OVERDUE, TickPhase.DATA_LOSS,
                TickPhase.EVALUATION, TickPhase.NO_READING, TickPhase.RETRY}) {
            a.armTick(new TickPlan(clock.now.plusSeconds(60), phase));
            assertFalse(phase.name(), port.alarmClock.containsKey(AlarmPort.Kind.TICK));
        }
        a.armWatchdog(clock.now.plusSeconds(60));
        a.armHeartbeat();
        assertFalse(port.alarmClock.containsKey(AlarmPort.Kind.WATCHDOG));
        assertFalse(port.alarmClock.containsKey(AlarmPort.Kind.HEARTBEAT));
    }

    @Test
    public void heartbeat_armedIn60s() {
        alarms(false).armHeartbeat();
        assertEquals(Long.valueOf(clock.elapsed + 60_000L), port.exact.get(AlarmPort.Kind.HEARTBEAT));
    }

    @Test
    public void cancelAll_cancelsTickWatchdogHeartbeatBootRetry() {
        MonitorAlarms a = alarms(false);
        a.armTick(new TickPlan(clock.now.plusSeconds(60), TickPhase.OVERDUE));
        a.armWatchdog(clock.now.plusSeconds(60));
        a.armHeartbeat();
        a.armBootRetry(Duration.ofSeconds(5));
        a.cancelAll();
        for (AlarmPort.Kind k : AlarmPort.Kind.values()) {
            assertFalse(k.name(), port.armed(k));
        }
    }
}
