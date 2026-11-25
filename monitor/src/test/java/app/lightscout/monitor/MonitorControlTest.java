package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.TickReason;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CP-08 §9 „JVM — IF-62”: enqueue on {@code monitorExecutor}, return immediately, serialised; service-start dispatch.
 */
public class MonitorControlTest {

    private Fakes.FakeSyncCycle cycle;
    private Fakes.FakeService service;
    private TickRunner runner;
    private ServiceLauncher launcher;

    @Before
    public void setUp() {
        Fakes.FakeClock clock = new Fakes.FakeClock();
        Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
        cycle = new Fakes.FakeSyncCycle();
        service = new Fakes.FakeService();
        MonitorAlarms alarms = new MonitorAlarms(new Fakes.FakeAlarmPort(), clock, logger, false);
        launcher = new ServiceLauncher(service, alarms, logger);
        runner = new TickRunner(() -> cycle, new Fakes.FakeMonitorStateStore(), new Fakes.FakeAlarmStateStore(),
                new Fakes.FakeSettingsStore(), new Fakes.FakeFlags(), new Fakes.FakeChannels(), alarms,
                new Fakes.FakeJobNet(), launcher, new Fakes.FakeWakeLock(), new Fakes.FakeBootCount(),
                new BootNotHandledGuard(), clock, logger);
    }

    @Test
    public void requestTick_andReevaluate_onlyEnqueue_returnImmediately() {
        List<Runnable> queued = new ArrayList<>();
        DefaultMonitorControl control = new DefaultMonitorControl(queued::add, runner, launcher);
        control.requestTick(TickReason.USER_RETRY);
        control.reevaluateAlarms();
        assertEquals(2, queued.size());
        assertTrue("nothing ran on the caller thread", cycle.calls.isEmpty());
        queued.forEach(Runnable::run);
        assertEquals(List.of("tick:USER_RETRY", "reevaluate"), cycle.calls);
    }

    @Test
    public void concurrentCalls_areSerialisedOnTheSingleThreadExecutor() throws Exception {
        ExecutorService monitorExecutor = Executors.newSingleThreadExecutor();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        cycle.sideEffect = () -> {
            int n = inside.incrementAndGet();
            maxInside.accumulateAndGet(n, Math::max);
            threads.add(Thread.currentThread().getName());
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
        };
        DefaultMonitorControl control = new DefaultMonitorControl(monitorExecutor, runner, launcher);
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        for (int i = 0; i < callers; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    control.requestTick(TickReason.ENTRY_WRITTEN);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        monitorExecutor.shutdown();
        assertTrue(monitorExecutor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(callers, cycle.calls.size());
        assertEquals(1, maxInside.get());
        assertEquals(1, threads.stream().distinct().count());
    }

    @Test
    public void startMonitoring_startsServiceFromActivityOrigin_withoutTick() {
        List<Runnable> queued = new ArrayList<>();
        DefaultMonitorControl control = new DefaultMonitorControl(queued::add, runner, launcher);
        control.startMonitoring();
        assertEquals(List.of(StartOrigin.ACTIVITY), service.starts);
        assertTrue(queued.isEmpty());
    }

    @Test
    public void dispatch_mapsEveryOriginToItsWork() {
        List<Runnable> queued = new ArrayList<>();
        DefaultMonitorControl control = new DefaultMonitorControl(queued::add, runner, launcher);
        cycle.onTick = r -> Fakes.result(FetchOutcome.OK, null, null, false);
        for (StartOrigin o : StartOrigin.values()) {
            control.dispatch(o);
        }
        queued.forEach(Runnable::run);
        assertTrue(cycle.calls.contains("tick:SCHEDULED"));
        assertTrue(cycle.calls.contains("tick:BOOT"));
        assertTrue(cycle.calls.contains("tick:PACKAGE_REPLACED"));
        assertTrue(cycle.calls.contains("tick:JOB_NET"));
        assertTrue(cycle.calls.contains("tick:SERVICE_RESTART"));
        assertTrue(cycle.calls.contains("tick:TIME_CHANGED"));
        assertTrue(cycle.calls.contains("reevaluate"));            // heartbeat
        assertFalse(cycle.calls.contains("tick:ONBOARDING_DONE"));  // ACTIVITY: the caller ticks
    }
}
