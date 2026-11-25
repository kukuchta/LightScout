package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.port.Callback;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * BUG-14: obtaining any service from the container (main thread in the app: ViewModel factories,
 * {@code ConnectionFragment.onCreate}) never creates a {@code NightscoutGateway} — creating the real one builds an
 * {@code OkHttpClient} (SSLContext init, a slow call, CP-00 §4). The gateway is created on the first gateway call, on
 * {@code ioExecutor} (IF-24 probe) or {@code monitorExecutor} (monitoring gateway, Q-46).
 */
public class GatewayFirstTouchTest {

    @Test
    public void connectionService_obtainingItCreatesNoGateway_probeCreatedOnIoByTheFirstCheck() throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        CountDownLatch hold = holdMonitorThread(container);

        ConnectionService service = container.get(ConnectionService.class);   // S-06 ConnectionFragment.onCreate

        assertEquals("no gateway created by obtaining IF-24", 0, c.created("gateway"));
        hold.countDown();
        drainMonitorThread(container);
        int afterStartUp = c.created("gateway");   // the monitoring gateway's start-up configure (monitor thread)

        CompletableFuture<Object> done = new CompletableFuture<>();
        Callback<List<CheckResult>> cb = done::complete;
        service.verify("https://ns.example.org", "token-0123456789abcdef".toCharArray(), cb);
        done.get(10, TimeUnit.SECONDS);

        assertEquals("the probe gateway was created by the first check", afterStartUp + 1, c.created("gateway"));
        assertOffMainThreads(c.gatewayThreads);
        assertTrue("probe created on an io thread: " + c.gatewayThreads,
                c.gatewayThreads.get(c.gatewayThreads.size() - 1).startsWith("lightscout-io-"));
    }

    @Test
    public void everyUiObtainedService_obtainingItCreatesNoGateway() throws Exception {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        CountDownLatch hold = holdMonitorThread(container);

        for (Class<?> type : List.of(StatusRepository.class, HistoryLoader.class, EntryWriter.class,
                ConnectionService.class, AlarmActions.class, SettingsService.class, OnboardingState.class)) {
            container.get(type);
        }
        container.monitorRuntime();   // MonitorService.onStartCommand (main)
        container.alarmRuntime();     // AlarmActivity (main)

        assertEquals("no gateway created on the obtaining (main) thread", 0, c.created("gateway"));
        hold.countDown();
        drainMonitorThread(container);
        // the monitoring gateway is first created by its start-up configure, on the monitor thread (Q-46)
        assertEquals(List.of("lightscout-monitor"), c.gatewayThreads);
    }

    private static void assertOffMainThreads(List<String> threads) {
        String test = Thread.currentThread().getName();
        for (String t : threads) {
            assertNotEquals("gateway created on the obtaining thread", test, t);
            assertTrue(t, t.startsWith("lightscout-io-") || t.equals("lightscout-monitor"));
        }
    }

    private static CountDownLatch holdMonitorThread(AppContainer container) {
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

    private static void drainMonitorThread(AppContainer container) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        container.monitorExecutor().execute(done::countDown);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }
}
