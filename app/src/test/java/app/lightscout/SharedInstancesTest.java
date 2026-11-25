package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.WriteResult;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.common.ServerEpoch;
import app.lightscout.usecases.common.SettingsWriter;
import app.lightscout.usecases.connection.ConnectionStateReset;
import app.lightscout.usecases.connection.OriginBoundTokenSource;
import app.lightscout.usecases.status.StatusProjector;
import app.lightscout.usecases.sync.SyncPorts;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * IR-106: the shared-instance rules of the SS-14 brief (rows 10, 11, 17; Q-46, IR-38, IR-44, IR-45, IR-52) are proven
 * at the capture point — the objects each use case actually holds (read from its fields by type) must be the
 * container's single instance. A mutation that gives any use case its own {@code new AlarmStateMemory()},
 * {@code new MonitorStateMemory()}, {@code new OriginBoundTokenSource(…)}, {@code new ServerEpoch()},
 * {@code new SettingsWriter(…)} or the wrong gateway fails here. Also IR-107 and IR-108 (JVM parts).
 */
public class SharedInstancesTest {

    private final TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
    private final AppContainer container = new AppContainer(c);

    @Test
    public void oneAlarmStateMemory_projectorCycleActionsResetRuntime() throws Exception {
        AlarmStateMemory shared = container.alarmStateMemory();
        assertAllSame(shared, projector(), AlarmStateMemory.class);
        assertAllSame(shared, container.syncCycle(), AlarmStateMemory.class);
        assertAllSame(shared, container.get(AlarmActions.class), AlarmStateMemory.class);
        assertAllSame(shared, reset(), AlarmStateMemory.class);
        assertSame(shared, c.alarmMemoryGivenToRuntime);
    }

    @Test
    public void oneMonitorStateMemory_projectorCycleResetHistory() throws Exception {
        MonitorStateMemory shared = container.monitorStateMemory();
        assertAllSame(shared, projector(), MonitorStateMemory.class);
        assertAllSame(shared, container.syncCycle(), MonitorStateMemory.class);
        assertAllSame(shared, reset(), MonitorStateMemory.class);
        assertAllSame(shared, container.get(HistoryLoader.class), MonitorStateMemory.class);
    }

    @Test
    public void oneProjector_withTheSharedLogger_bug02() throws Exception {
        StatusProjector projector = projector();
        assertAllSame(projector, container.syncCycle(), StatusProjector.class);
        assertAllSame(container.logger(), projector, Logger.class);
    }

    @Test
    public void oneOriginBoundTokenSource_monitoringGatewayEntryWriterReset() throws Exception {
        OriginBoundTokenSource shared = container.tokens();
        assertAllSame(shared, container.get(EntryWriter.class), OriginBoundTokenSource.class);
        assertAllSame(shared, reset(), OriginBoundTokenSource.class);
        drainMonitorThread();
        assertSame(shared, TestDoubles.fake(container.monitoringGateway()).tokens);
    }

    @Test
    public void twoGateways_monitoringForCycleHistoryEntryReset_probeForConnectionServiceOnly() throws Exception {
        NightscoutGateway monitoring = container.monitoringGateway();
        NightscoutGateway probe = container.probeGateway();
        assertNotSame(monitoring, probe);
        SyncPorts ports = single(container.syncCycle(), SyncPorts.class);
        assertSame(monitoring, ports.gateway());
        assertAllSame(monitoring, container.get(EntryWriter.class), NightscoutGateway.class);
        assertAllSame(monitoring, container.get(HistoryLoader.class), NightscoutGateway.class);
        assertAllSame(monitoring, reset(), NightscoutGateway.class);
        assertAllSame(probe, container.get(ConnectionService.class), NightscoutGateway.class);
    }

    @Test
    public void oneServerEpoch_resetHistoryEntryWriter() throws Exception {
        ServerEpoch shared = container.serverEpoch();
        assertAllSame(shared, reset(), ServerEpoch.class);
        assertAllSame(shared, container.get(HistoryLoader.class), ServerEpoch.class);
        assertAllSame(shared, container.get(EntryWriter.class), ServerEpoch.class);
    }

    @Test
    public void oneSettingsWriter_alarmActionsAndSettingsService() throws Exception {
        SettingsWriter shared = container.settingsWriter();
        assertAllSame(shared, container.get(AlarmActions.class), SettingsWriter.class);
        assertAllSame(shared, container.get(SettingsService.class), SettingsWriter.class);
    }

    @Test
    public void ifSixteen_isTheAlarmRuntime_inCycleAndActions() throws Exception {
        AlarmController controller = container.alarmController();
        assertSame(c.alarmController, controller);
        assertSame(controller, single(container.syncCycle(), SyncPorts.class).alarmController());
        assertAllSame(controller, container.get(AlarmActions.class), AlarmController.class);
    }

    // ------------------------------------------------------------------ IR-107

    @Test
    public void ir107_ioFirstUse_waitsForTheConfigurationOnTheMonitorThread_neverUsesAnUnconfiguredGateway()
            throws Exception {
        CountDownLatch hold = holdMonitorThread();
        EntryWriter writer = container.get(EntryWriter.class);
        TestDoubles.FakeGateway monitoring = TestDoubles.fake(container.monitoringGateway());
        CompletableFuture<Result<WriteResult>> done = new CompletableFuture<>();
        Callback<WriteResult> cb = done::complete;
        try {
            writer.delete("abc", cb);
            Thread.sleep(300);
            assertFalse("the io task waits for the monitor thread", done.isDone());
            assertTrue("no gateway call before the configuration", monitoring.calls.isEmpty());
        } finally {
            hold.countDown();
        }
        done.get(5, TimeUnit.SECONDS);
        assertEquals("configured once", List.of("https://ns.example.org"), monitoring.configured);
        assertTrue("token source bound -> the request reached the gateway", monitoring.calls.contains("get"));
    }

    // ------------------------------------------------------------------ IR-118

    @Test
    public void ir118_configureRunsOnlyOnTheMonitorThread_andNeverOverwritesAConcurrentReset() throws Exception {
        CountDownLatch hold = holdMonitorThread();
        EntryWriter writer = container.get(EntryWriter.class);   // posts the start-up step behind the hold
        TestDoubles.FakeGateway monitoring = TestDoubles.fake(container.monitoringGateway());
        OriginBoundTokenSource tokens = container.tokens();
        // an R-71 server change: the store already holds the new URL, the reset task is queued on the single writer
        container.monitorExecutor().execute(() -> {
            c.storedUrl = "https://new.example.org";
            tokens.bind("https://new.example.org");
            monitoring.configure("https://new.example.org", tokens);
        });
        CompletableFuture<Result<WriteResult>> done = new CompletableFuture<>();
        Callback<WriteResult> cb = done::complete;
        writer.delete("abc", cb);                                  // io first use while the queue is held
        Thread.sleep(200);
        hold.countDown();
        done.get(5, TimeUnit.SECONDS);
        drainMonitorThread();
        assertEquals("start-up step, then the reset - nothing after it",
                List.of("https://ns.example.org", "https://new.example.org"), monitoring.configured);
        for (String thread : monitoring.configuredOn) {
            assertEquals("every configure on the single writer (Q-46)", "lightscout-monitor", thread);
        }
        assertTrue("token source still bound to the stored (new) server", tokens.boundToStoredServer());
    }

    // ------------------------------------------------------------------ IR-108 (JVM part)

    @Test
    public void ir108_noBootCountCheckWhileBuildingTheContainerOrTheGraph() {
        TestDoubles.FakeComponents locked = new TestDoubles.FakeComponents(false);
        AppContainer early = new AppContainer(locked);
        early.monitorBootDependencies().flags().monitoringConfigured();
        assertEquals(0, locked.created("bootCountCheck"));
        container.get(StatusRepository.class);
        container.monitorRuntime();
        container.alarmRuntime();
        assertEquals(0, c.created("bootCountCheck"));
        assertEquals(0, c.bootChecks.get());
    }

    // ------------------------------------------------------------------ helpers

    private StatusProjector projector() throws Exception {
        return single(container.get(StatusRepository.class), StatusProjector.class);
    }

    private ConnectionStateReset reset() throws Exception {
        return single(container.get(ConnectionService.class), ConnectionStateReset.class);
    }

    private static <T> void assertAllSame(T expected, Object owner, Class<T> type) throws Exception {
        List<T> held = fieldsOfType(owner, type);
        assertFalse(owner.getClass().getSimpleName() + " holds a " + type.getSimpleName(), held.isEmpty());
        for (T value : held) {
            assertSame(owner.getClass().getSimpleName() + "." + type.getSimpleName() + " is the shared instance",
                    expected, value);
        }
    }

    private static <T> T single(Object owner, Class<T> type) throws Exception {
        List<T> held = fieldsOfType(owner, type);
        assertEquals(owner.getClass().getSimpleName() + " holds one " + type.getSimpleName(), 1, held.size());
        return held.get(0);
    }

    private static <T> List<T> fieldsOfType(Object owner, Class<T> type) throws Exception {
        List<T> out = new ArrayList<>();
        for (Class<?> k = owner.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null) {
                        out.add(type.cast(v));
                    }
                }
            }
        }
        return out;
    }

    private CountDownLatch holdMonitorThread() {
        CountDownLatch hold = new CountDownLatch(1);
        container.monitorExecutor().execute(() -> {
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return hold;
    }

    private void drainMonitorThread() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        container.monitorExecutor().execute(done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }
}
