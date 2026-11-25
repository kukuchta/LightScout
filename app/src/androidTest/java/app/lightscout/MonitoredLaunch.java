package app.lightscout;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Launches an activity with {@code Context.startActivity} and observes it through
 * {@link ActivityLifecycleMonitorRegistry} (BUG-08, ruling Q-95). For launch intents that carry data / extras or a
 * non-MAIN action (SR-21 attack input): {@code MainActivity} replaces such an intent with its clean intent, so
 * {@code ActivityScenario}, which matches its activity against the launch intent, never recognises it.
 *
 * <p>The initial start clears the task ({@code NEW_TASK | CLEAR_TASK}), so no activity left over from an earlier
 * test sits in it. The first new instance of the type (seen at {@link Stage#PRE_ON_CREATE} after {@link #start}) is
 * the observed one; every instance created later is tracked as well. {@link #close()} finishes every tracked
 * instance that is not yet destroyed and waits until all are; use it in try-with-resources.
 *
 * @param <A> the activity type
 */
final class MonitoredLaunch<A extends Activity> implements AutoCloseable {

    /** Generous for cold starts on HyperOS (RD-1). */
    private static final long TIMEOUT_MS = 30_000;

    private final Class<A> type;
    private final AtomicReference<A> activity = new AtomicReference<>();
    private final List<A> instances = new CopyOnWriteArrayList<>();
    private final Map<A, Stage> stages = new ConcurrentHashMap<>();
    private final ActivityLifecycleCallback callback = this::onLifecycle;

    private MonitoredLaunch(Class<A> type) {
        this.type = type;
    }

    /**
     * Starts {@code intent} from the application context in a cleared new task ({@code FLAG_ACTIVITY_NEW_TASK |
     * FLAG_ACTIVITY_CLEAR_TASK}) and waits until the new {@code type} instance is resumed. Later intents to the
     * running activity (new-intent tests) are started by the test itself and must not clear the task.
     *
     * @param type the activity class the intent targets
     * @param intent the launch intent, unchanged apart from the two task flags
     * @param <A> the activity type
     * @return the observed launch
     */
    static <A extends Activity> MonitoredLaunch<A> start(Class<A> type, Intent intent) {
        MonitoredLaunch<A> launch = new MonitoredLaunch<>(type);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(launch.callback));
        try {
            Context context = ApplicationProvider.getApplicationContext();
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            launch.awaitObserved(Stage.RESUMED);
        } catch (RuntimeException | AssertionError e) {
            try {
                launch.close();
            } catch (RuntimeException | AssertionError t) {
                e.addSuppressed(t);
            }
            throw e;
        }
        return launch;
    }

    private void onLifecycle(Activity a, Stage s) {
        if (!type.isInstance(a)) {
            return;
        }
        A instance = type.cast(a);
        if (s == Stage.PRE_ON_CREATE && !instances.contains(instance)) {
            instances.add(instance);
            activity.compareAndSet(null, instance);
        }
        if (instances.contains(instance)) {
            stages.put(instance, s);
        }
    }

    /**
     * Runs {@code action} on the main thread with the observed activity (like {@code ActivityScenario.onActivity}).
     *
     * @param action the action
     */
    void onActivity(ActivityScenario.ActivityAction<A> action) {
        A a = activity.get();
        assertNotNull("no " + type.getSimpleName() + " instance observed", a);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> action.perform(a));
    }

    /**
     * Returns the number of new {@code type} instances created since {@link #start}.
     *
     * @return the instance count
     */
    int instances() {
        return instances.size();
    }

    private void awaitObserved(Stage wanted) {
        long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < end && stageOf(activity.get()) != wanted) {
            SystemClock.sleep(50);
        }
        assertTrue(type.getSimpleName() + " never reached " + wanted + " (last stage " + stageOf(activity.get())
                + ")", stageOf(activity.get()) == wanted);
    }

    private Stage stageOf(A a) {
        return a == null ? null : stages.get(a);
    }

    /**
     * Finishes every tracked instance that is not yet destroyed, waits until all of them are destroyed, and removes
     * the lifecycle callback.
     */
    @Override
    public void close() {
        try {
            List<A> alive = new ArrayList<>();
            for (A a : instances) {
                if (stages.get(a) != Stage.DESTROYED) {
                    alive.add(a);
                }
            }
            if (!alive.isEmpty()) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    for (A a : alive) {
                        a.finish();
                    }
                });
            }
            long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
            while (SystemClock.uptimeMillis() < end && !allDestroyed()) {
                SystemClock.sleep(50);
            }
            assertTrue("every " + type.getSimpleName() + " destroyed: " + stages.values(), allDestroyed());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback));
        }
    }

    private boolean allDestroyed() {
        for (A a : instances) {
            if (stages.get(a) != Stage.DESTROYED) {
                return false;
            }
        }
        return true;
    }
}
