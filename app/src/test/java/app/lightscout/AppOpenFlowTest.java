package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** {@code MainActivity.onStart} APP_OPEN path (CP-00A R-86, SS-09 §5.5–5.6, Q-67). */
public class AppOpenFlowTest {

    private final List<String> order = new ArrayList<>();
    private final TestDoubles.FakeMonitorControl control = new TestDoubles.FakeMonitorControl();
    private final TestDoubles.FakeFlags flags = new TestDoubles.FakeFlags(new TestDoubles.CallLog());
    private final AtomicInteger bootChecks = new AtomicInteger();
    private final Executor io = task -> {
        order.add("io");
        task.run();
    };
    private final Executor main = task -> {
        order.add("main");
        task.run();
    };
    private final AppOpenFlow flow = new AppOpenFlow(io, main, flags, () -> control,
            () -> bootChecks::incrementAndGet);

    @Test
    public void onboardingCompleted_startsMonitoringThenAppOpenTickThenBootCheckOffMain() {
        flags.onboardingCompleted = true;
        flow.onStart(() -> true);
        assertEquals(List.of("startMonitoring", "requestTick:APP_OPEN"), control.calls);
        assertEquals(1, bootChecks.get());
        assertEquals("flag read on io, start on main, boot check on io", List.of("io", "main", "io"), order);
    }

    @Test
    public void onboardingNotCompleted_nothingStarted_noBootCheck() {
        flow.onStart(() -> true);
        assertTrue(control.calls.isEmpty());
        assertEquals(0, bootChecks.get());
        assertEquals(List.of("io"), order);
    }

    @Test
    public void activityNoLongerStarted_noForegroundServiceStart() {
        flags.onboardingCompleted = true;
        flow.onStart(() -> false);
        assertTrue(control.calls.isEmpty());
        assertEquals(0, bootChecks.get());
    }

    @Test
    public void ceReadFailure_dEFlagDecides() {
        TestDoubles.FakeFlags failing = new TestDoubles.FakeFlags(new TestDoubles.CallLog()) {
            @Override
            public boolean onboardingCompleted() {
                throw new app.lightscout.domain.port.StorageException("STORAGE", null);
            }
        };
        failing.monitoringConfigured = true;
        new AppOpenFlow(io, main, failing, () -> control, () -> bootChecks::incrementAndGet).onStart(() -> true);
        assertEquals(List.of("startMonitoring", "requestTick:APP_OPEN"), control.calls);
    }
}
