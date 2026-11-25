package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.status.TickReason;

import org.junit.Test;

import java.time.Duration;
import java.util.List;

/**
 * 06 §11 F4 start-origin table: {@code FGS_START_DENIED} + retry per origin (WF-04 5 s); dispatch; allow-lists of
 * the alarm receivers; origin parsing (unknown → tick, never nothing).
 */
public class ServiceStartTest {

    private final Fakes.FakeClock clock = new Fakes.FakeClock();
    private final Fakes.FakeAlarmPort port = new Fakes.FakeAlarmPort();
    private final Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
    private final Fakes.FakeService service = new Fakes.FakeService();
    private final ServiceLauncher launcher =
            new ServiceLauncher(service, new MonitorAlarms(port, clock, logger, false), logger);

    @Test
    public void accepted_noLog_noRetry() {
        assertTrue(launcher.launch(StartOrigin.TICK));
        assertTrue(logger.lines.isEmpty());
        assertTrue(port.calls.isEmpty());
    }

    @Test
    public void refusedFromTick_loggedAndTickRetriedIn60s() {
        service.refuse = true;
        assertFalse(launcher.launch(StartOrigin.TICK));
        assertEquals(List.of("FGS_START_DENIED:TICK"), logger.lines);
        assertEquals(Long.valueOf(clock.elapsed + 60_000L), port.exact.get(AlarmPort.Kind.TICK));
    }

    @Test
    public void refusedFromHeartbeat_heartbeatRetriedIn60s() {
        service.refuse = true;
        launcher.launch(StartOrigin.HEARTBEAT);
        assertEquals(Long.valueOf(clock.elapsed + 60_000L), port.exact.get(AlarmPort.Kind.HEARTBEAT));
    }

    @Test
    public void wf04_refusedFromBootOrUpdate_exactAlarmIn5s() {
        service.refuse = true;
        launcher.launch(StartOrigin.BOOT);
        assertEquals(Long.valueOf(clock.elapsed + 5_000L), port.exact.get(AlarmPort.Kind.BOOT_RETRY));
        launcher.launch(StartOrigin.PACKAGE_REPLACED);
        assertEquals(Long.valueOf(clock.elapsed + 5_000L), port.exact.get(AlarmPort.Kind.BOOT_RETRY));
        launcher.launch(StartOrigin.BOOT_RETRY);
        assertEquals(Long.valueOf(clock.elapsed + 60_000L), port.exact.get(AlarmPort.Kind.BOOT_RETRY));
    }

    @Test
    public void refusedFromWatchdogJobOrTime_noOwnRetry_otherNetsCover() {
        service.refuse = true;
        for (StartOrigin o : new StartOrigin[] {StartOrigin.WATCHDOG, StartOrigin.JOB_NET, StartOrigin.TIME_CHANGED,
                StartOrigin.ACTIVITY}) {
            launcher.launch(o);
        }
        assertTrue(port.calls.isEmpty());
        assertEquals(4, logger.count("FGS_START_DENIED"));
    }

    @Test
    public void dispatch_workAndReasons() {
        assertEquals(StartDispatch.Work.NONE, StartDispatch.work(StartOrigin.ACTIVITY));
        assertEquals(StartDispatch.Work.WATCHDOG, StartDispatch.work(StartOrigin.WATCHDOG));
        assertEquals(StartDispatch.Work.HEARTBEAT, StartDispatch.work(StartOrigin.HEARTBEAT));
        assertEquals(StartDispatch.Work.TICK, StartDispatch.work(StartOrigin.STICKY_RESTART));
        assertEquals(TickReason.SERVICE_RESTART, StartDispatch.reason(StartOrigin.STICKY_RESTART));
        assertEquals(TickReason.BOOT, StartDispatch.reason(StartOrigin.BOOT_RETRY));
        assertEquals(TickReason.SCHEDULED, StartDispatch.reason(StartOrigin.TICK));
        assertTrue(StartDispatch.resumesAfterBoot(StartOrigin.BOOT));
        assertFalse(StartDispatch.resumesAfterBoot(StartOrigin.TICK));
        assertEquals(Duration.ofSeconds(5), StartDispatch.retryDelay(StartOrigin.BOOT));
    }

    @Test
    public void ir72_foregroundRefusal_retriesHeartbeatWhenRinging_elseTick() {
        assertEquals(StartDispatch.Retry.TICK, StartDispatch.afterForegroundRefusal(StartOrigin.TICK, false));
        assertEquals(StartDispatch.Retry.TICK, StartDispatch.afterForegroundRefusal(StartOrigin.BOOT, false));
        assertEquals(StartDispatch.Retry.HEARTBEAT, StartDispatch.afterForegroundRefusal(StartOrigin.TICK, true));
        assertEquals(StartDispatch.Retry.HEARTBEAT,
                StartDispatch.afterForegroundRefusal(StartOrigin.HEARTBEAT, false));
    }

    @Test
    public void alarmReceiver_allowList() {
        assertEquals(StartOrigin.TICK, MonitorAlarmReceiver.originFor(MonitorIntents.ACTION_TICK));
        assertEquals(StartOrigin.HEARTBEAT, MonitorAlarmReceiver.originFor(MonitorIntents.ACTION_HEARTBEAT));
        assertEquals(StartOrigin.BOOT_RETRY, MonitorAlarmReceiver.originFor(MonitorIntents.ACTION_BOOT_RETRY));
        assertNull(MonitorAlarmReceiver.originFor(MonitorIntents.ACTION_WATCHDOG));
        assertNull(MonitorAlarmReceiver.originFor("android.intent.action.BOOT_COMPLETED"));
        assertNull(MonitorAlarmReceiver.originFor(null));
    }

    @Test
    public void originParsing_unknownOrMissing_isTreatedAsRestartTick() {
        assertEquals(StartOrigin.WATCHDOG, MonitorIntents.parseOrigin("WATCHDOG"));
        assertEquals(StartOrigin.STICKY_RESTART, MonitorIntents.parseOrigin(null));
        assertEquals(StartOrigin.STICKY_RESTART, MonitorIntents.parseOrigin("bogus"));
    }
}
