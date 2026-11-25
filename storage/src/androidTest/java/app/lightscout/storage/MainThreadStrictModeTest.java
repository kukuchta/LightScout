package app.lightscout.storage;

import static app.lightscout.storage.StorageTestSupport.reading;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.StrictMode;
import android.os.strictmode.Violation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TST-05-19: a store call from the main thread is caught by StrictMode disk detection (the debug policy of
 * {@code :app} is detectAll + penaltyDeath; here a penalty listener records the violation instead of killing
 * the test process).
 *
 * <p>Device: RD-2 (API 33) over USB — batched pre-M1 run (CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class MainThreadStrictModeTest {

    private Context context;

    @Before
    public void setUp() {
        context = StorageTestSupport.context();
        StorageTestSupport.wipe(context);
    }

    @After
    public void tearDown() {
        StorageTestSupport.wipe(context);
    }

    @Test
    public void tst0519_storeCallOnTheMainThreadIsAStrictModeViolation() throws Exception {
        StorageFactory factory = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger());
        CountDownLatch violated = new CountDownLatch(1);
        AtomicReference<Violation> seen = new AtomicReference<>();
        Executor listenerExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<StrictMode.ThreadPolicy> previous = new AtomicReference<>();
        // On a Looper thread StrictMode queues the violation and reports it after the current message,
        // reading the penalty listener from the thread policy at report time. The old policy is therefore
        // restored only after the violation has been reported (or the wait timed out), in a later message.
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                previous.set(StrictMode.getThreadPolicy());
                StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyListener(listenerExecutor, v -> {
                            seen.set(v);
                            violated.countDown();
                        })
                        .build());
                factory.readings().upsert(List.of(reading("r1", 0, 100)));
            });
            assertTrue("StrictMode must flag disk access on main", violated.await(10, TimeUnit.SECONDS));
        } finally {
            StrictMode.ThreadPolicy old = previous.get();
            if (old != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> StrictMode.setThreadPolicy(old));
            }
        }
        assertTrue(seen.get() != null);
    }
}
