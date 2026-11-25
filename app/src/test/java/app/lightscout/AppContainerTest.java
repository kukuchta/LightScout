package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.monitor.MonitorBootDependencies;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.SyncCycle;
import app.lightscout.usecases.connection.OriginBoundTokenSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * CP-15 §9 „Container laziness” + §11 „every port bound to exactly one implementation; two named executors”
 * (construction counters on fakes, JVM).
 */
public class AppContainerTest {

    private static final Set<String> LOCKED_ALLOWED = Set.of("clock", "logger", "channels", "appFlags");

    private static final List<Class<?>> REGISTERED = List.of(StatusRepository.class, HistoryLoader.class,
            EntryWriter.class, ConnectionService.class, AlarmActions.class, SettingsService.class,
            OnboardingState.class, ReadinessChecker.class, NotificationChannels.class, SystemSettingsIntents.class,
            ManufacturerDetector.class, Clock.class, EntryRules.class);

    // ------------------------------------------------------------------ locked (direct boot)

    @Test
    public void locked_constructionBuildsOnlyLoggerClockAndChannels() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(false);
        new AppContainer(c);
        assertEquals(Set.of("clock", "logger", "channels"), c.created.keySet());
        assertTrue("no store call at construction", c.storeCalls.calls.isEmpty());
    }

    @Test
    public void locked_bootDependenciesWorkAndBuildOnlyTheAllowedThree() throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(false);
        AppContainer container = new AppContainer(c);
        MonitorBootDependencies boot = container.monitorBootDependencies();
        boot.flags().monitoringConfigured();
        boot.flags().setHandledBootCount(3);
        boot.channels().ensureChannels();
        boot.logger().debug("t", "m");
        assertNotNull(boot.clock().now());
        CountDownLatch ran = new CountDownLatch(1);
        boot.receiverExecutor().execute(ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertEquals(LOCKED_ALLOWED, c.created.keySet());
        assertEquals(1, c.created("appFlags"));
    }

    @Test
    public void locked_receiverExecutorIsNotTheMonitorThreadAndIsSingleThreaded() throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        String receiver = threadName(container.monitorBootDependencies().receiverExecutor()::execute);
        String monitor = threadName(container.monitorExecutor()::execute);
        assertEquals("lightscout-receiver", receiver);
        assertEquals("lightscout-monitor", monitor);
    }

    @Test
    public void locked_everyCeComponentIsRefusedAndNothingIsBuilt() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(false);
        AppContainer container = new AppContainer(c);
        for (Class<?> type : REGISTERED) {
            if (type == NotificationChannels.class || type == Clock.class || type == EntryRules.class) {
                assertNotNull(container.get(type)); // locked-safe tier (EntryRules: pure, no storage)
                continue;
            }
            assertThrows(type.getSimpleName(), IllegalStateException.class, () -> container.get(type));
        }
        assertThrows(IllegalStateException.class, container::monitorRuntime);
        assertThrows(IllegalStateException.class, container::alarmRuntime);
        assertThrows(IllegalStateException.class, container::monitorControl);
        assertThrows(IllegalStateException.class, container::ioExecutor);
        assertThrows(IllegalStateException.class, container::monitorExecutor);
        assertEquals(Set.of("clock", "logger", "channels"), c.created.keySet());
        assertTrue(c.storeCalls.calls.isEmpty());
    }

    @Test
    public void afterUnlock_firstAccessBuildsTheCeComponent() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(false);
        AppContainer container = new AppContainer(c);
        assertThrows(IllegalStateException.class, () -> container.get(StatusRepository.class));
        c.unlocked = true;
        assertNotNull(container.get(StatusRepository.class));
        assertEquals(1, c.created("monitorRuntime"));
        assertEquals(1, c.created("connectivity"));
    }

    // ------------------------------------------------------------------ unlocked wiring

    @Test
    public void unlocked_everyRegisteredTypeResolvesToOneInstance() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        for (Class<?> type : REGISTERED) {
            Object first = container.get(type);
            assertNotNull(type.getSimpleName(), first);
            assertTrue(type.isInstance(first));
            assertSame(type.getSimpleName(), first, container.get(type));
        }
    }

    @Test
    public void unknownType_throwsIllegalStateException() {
        AppContainer container = new AppContainer(new TestDoubles.FakeComponents(true));
        assertThrows(IllegalStateException.class, () -> container.get(SyncCycle.class));
        assertThrows(IllegalStateException.class, () -> container.get(String.class));
    }

    @Test
    public void unlocked_wiringTouchesNoStorage_storesAreCreatedOnFirstCallOnly() throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        // hold the monitor thread: the posted start-up configuration (a store call) must not interfere here
        CountDownLatch hold = new CountDownLatch(1);
        container.monitorExecutor().execute(() -> {
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (Class<?> type : REGISTERED) {
            container.get(type);
        }
        container.monitorRuntime();
        container.alarmRuntime();
        container.syncCycle();
        // building the whole graph (main thread in the app) creates no :storage store and makes no store call
        for (String store : List.of("readings", "entries", "deviceStatus", "settings", "connection", "monitorState",
                "alarmState", "reminderState", "notifiedAlerts", "appFlags")) {
            assertEquals(store, 0, c.created(store));
        }
        assertTrue(c.storeCalls.calls.toString(), c.storeCalls.calls.isEmpty());
        hold.countDown();
        drainMonitorThread(container);
        // the start-up configuration ran on the monitor thread: only the connection store was created, there
        assertEquals(1, c.created("connection"));
        assertEquals(List.of("connection.baseUrl"), c.storeCalls.calls);
    }

    @Test
    public void everyComponentIsBuiltAtMostOnce() throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        for (int i = 0; i < 3; i++) {
            for (Class<?> type : REGISTERED) {
                container.get(type);
            }
            container.monitorRuntime();
            container.alarmRuntime();
            container.syncCycle();
        }
        // IR-122: the posted start-up step creates the connection store on the monitor thread — wait for it
        drainMonitorThread(container);
        for (String name : c.created.keySet()) {
            assertEquals(name, 1, c.created(name));
        }
        // BUG-14: gateways are created on the first gateway call only — after wiring that is just the monitoring
        // gateway's start-up configure on the monitor thread; the probe gateway does not exist yet
        assertEquals(List.of("lightscout-monitor"), c.gatewayThreads);
    }

    @Test
    public void twoGatewayInstances_monitoringAndProbe() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        container.get(ConnectionService.class);
        container.get(EntryWriter.class);
        container.get(HistoryLoader.class);
        container.syncCycle();
        assertNotSame(container.monitoringGateway(), container.probeGateway());
        // BUG-14: each wrapper resolves to its own real gateway (created on the first call)
        assertNotSame(TestDoubles.fake(container.monitoringGateway()), TestDoubles.fake(container.probeGateway()));
        assertEquals(2, c.gateways.size());
    }

    @Test
    public void monitoringGateway_boundAndConfiguredOnTheMonitorThread_probeNeverConfiguredAtStartUp()
            throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        container.get(EntryWriter.class);          // builds the monitoring gateway on this (non-monitor) thread
        container.get(ConnectionService.class);    // builds the probe gateway
        drainMonitorThread(container);
        TestDoubles.FakeGateway monitoring = TestDoubles.fake(container.monitoringGateway());
        TestDoubles.FakeGateway probe = TestDoubles.fake(container.probeGateway());
        assertEquals(List.of("https://ns.example.org"), monitoring.configured);
        assertSame("the one shared OriginBoundTokenSource", container.tokens(), monitoring.tokens);
        assertTrue(container.tokens().boundToStoredServer());
        assertTrue(probe.configured.isEmpty());
        assertTrue("token readable for the bound origin", container.tokens().token().isPresent());
    }

    @Test
    public void monitoringGateway_noStoredConnection_boundToNothingAndNotConfigured() throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        c.storedUrl = null;
        AppContainer container = new AppContainer(c);
        container.get(EntryWriter.class);
        drainMonitorThread(container);
        OriginBoundTokenSource tokens = container.tokens();
        assertEquals(Optional.empty(), tokens.token());
        assertTrue(TestDoubles.fake(container.monitoringGateway()).configured.isEmpty());
    }

    @Test
    public void syncCycleSupplier_resolvedOnTheMonitorThread_configuresBeforeTheTickUsesTheGateway()
            throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        container.monitorRuntime();
        CompletableFuture<List<String>> configuredWhenResolved = new CompletableFuture<>();
        container.monitorExecutor().execute(() -> {
            c.monitorCollaborators.syncCycle().get();
            TestDoubles.FakeGateway g = TestDoubles.fake(container.monitoringGateway());
            configuredWhenResolved.complete(List.copyOf(g.configured));
        });
        assertEquals(List.of("https://ns.example.org"), configuredWhenResolved.get(5, TimeUnit.SECONDS));
        drainMonitorThread(container);
        assertEquals("configured once", 1,
                TestDoubles.fake(container.monitoringGateway()).configured.size());
    }

    @Test
    public void sharedInstances_runtimesAndExecutor() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        container.get(AlarmActions.class);
        container.monitorRuntime();
        assertSame("AlarmRuntime gets the one AlarmStateMemory", container.alarmStateMemory(),
                c.alarmMemoryGivenToRuntime);
        assertSame("AlarmRuntime runs on monitorExecutor", container.monitorExecutor(), c.alarmRuntimeExecutor);
        assertSame("MonitorRuntime runs on monitorExecutor", container.monitorExecutor(),
                c.monitorCollaborators.monitorExecutor());
        assertSame(container.flags(), c.monitorCollaborators.flags());
        assertSame("Supplier<AlarmActions> yields the registered IF-25", container.get(AlarmActions.class),
                c.alarmActionsSupplier.get());
        assertSame("Supplier<StatusRepository> yields IF-21", container.get(StatusRepository.class),
                c.monitorCollaborators.status().get());
        assertSame("Supplier<SyncCycle> yields the one cycle", container.syncCycle(),
                c.monitorCollaborators.syncCycle().get());
        assertSame(c.control, container.monitorControl());
    }

    @Test
    public void ioExecutor_hasThreeNamedThreads() throws Exception {
        AppContainer container = new AppContainer(new TestDoubles.FakeComponents(true));
        Set<String> names = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CountDownLatch inside = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 3; i++) {
            container.ioExecutor().execute(() -> {
                names.add(Thread.currentThread().getName());
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue("three tasks run in parallel", inside.await(5, TimeUnit.SECONDS));
        release.countDown();
        assertEquals(Set.of("lightscout-io-1", "lightscout-io-2", "lightscout-io-3"), names);
    }

    @Test
    public void appInfo_carriesVersionName() {
        AppContainer container = new AppContainer(new TestDoubles.FakeComponents(false));
        assertEquals("1.2.3", container.appInfo().versionName());
    }

    @Test
    public void onboardingCompleteOnboarding_startsMonitoringThroughTheRuntimeControl() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        container.get(OnboardingState.class).completeOnboarding();
        assertTrue(c.control.calls.toString(), c.control.calls.contains("startMonitoring"));
        assertTrue(c.control.calls.toString(), c.control.calls.contains("requestTick:ONBOARDING_DONE"));
    }

    private static void drainMonitorThread(AppContainer container) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        container.monitorExecutor().execute(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    private static String threadName(java.util.function.Consumer<Runnable> executor) throws Exception {
        CompletableFuture<String> name = new CompletableFuture<>();
        executor.accept(() -> name.complete(Thread.currentThread().getName()));
        return name.get(5, TimeUnit.SECONDS);
    }
}
