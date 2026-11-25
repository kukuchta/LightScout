package app.lightscout.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.os.StrictMode;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.lightscout.platform.channels.AndroidChannelBackend;
import app.lightscout.platform.channels.DefaultNotificationChannels;
import app.lightscout.platform.readiness.AndroidReadinessProbe;
import app.lightscout.platform.readiness.DefaultReadinessChecker;

/**
 * CP-05 §9 „Readiness per band” (real-device part) and „Readiness cost”: on the phone's band the item visibility
 * matches §8.2, and {@code check()} does no disk I/O on the main thread and returns quickly. The other bands are
 * JVM tests with an SDK-level fake (CR-04); item 3 on 34+ is observed on RD-1 ({@code VR-27.md} §6).
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class ReadinessDeviceTest {

    private static final long MAX_CHECK_MS = 50;
    private static final int WARM_UP_CALLS = 5;
    private static final int MEASURED_CALLS = 50;

    private DefaultReadinessChecker checker() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DefaultNotificationChannels channels =
                new DefaultNotificationChannels(new AndroidChannelBackend(context), new RecordingLogger());
        channels.ensureChannels();
        return new DefaultReadinessChecker(new AndroidReadinessProbe(context, channels));
    }

    @Test
    public void itemsMatchThePhoneBand() {
        List<ReadinessItem> items = checker().check();
        assertEquals(7, items.size());
        assertEquals(ReadinessItemId.MANUFACTURER, items.get(6).id());
        for (ReadinessItem item : items) {
            switch (item.id()) {
                case NOTIFICATIONS -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        assertTrue(item.shown());
                    }
                }
                case FULL_SCREEN -> assertEquals(Build.VERSION.SDK_INT >= 34, item.shown());
                case EXACT_ALARM -> {
                    if (Build.VERSION.SDK_INT < 31) {
                        assertFalse(item.shown());
                    }
                }
                case MANUFACTURER -> {
                    assertTrue(item.shown());
                    assertFalse(item.required());
                    assertEquals(ReadinessStatus.RECOMMENDED, item.status());
                }
                default -> assertTrue(item.id() + " always shown", item.shown());
            }
        }
    }

    @Test
    public void checkIsCheapAndDiskFreeOnMainThread() {
        DefaultReadinessChecker checker = checker();
        List<String> violations = new CopyOnWriteArrayList<>();
        long[] millis = new long[MEASURED_CALLS];
        StrictMode.ThreadPolicy[] previous = new StrictMode.ThreadPolicy[1];
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                previous[0] = StrictMode.getThreadPolicy();
                StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .detectNetwork()
                        .penaltyListener(Runnable::run, v -> violations.add(v.getClass().getSimpleName()))
                        .build());
                // Warm-up calls run under the same policy (no disk I/O allowed there either) but are not timed:
                // the cold first call includes class loading / binder set-up and is not the steady-state cost.
                for (int i = 0; i < WARM_UP_CALLS; i++) {
                    checker.check();
                    checker.missingRequiredCount();
                }
                for (int i = 0; i < MEASURED_CALLS; i++) {
                    long start = System.nanoTime();
                    checker.check();
                    checker.missingRequiredCount();
                    millis[i] = (System.nanoTime() - start) / 1_000_000;
                }
            });
        } finally {
            // On a Looper thread StrictMode reports violations after the current message and reads the listener
            // from the thread policy at report time, so the policy is restored only in a later message.
            if (previous[0] != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> StrictMode.setThreadPolicy(previous[0]));
            }
        }
        assertTrue(violations.toString(), violations.isEmpty());
        long worst = Arrays.stream(millis).max().orElse(0);
        assertTrue("worst after warm-up " + worst + " ms", worst <= MAX_CHECK_MS);
    }
}
