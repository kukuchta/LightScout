package app.lightscout.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.ZoneId;

import app.lightscout.platform.clock.AndroidClock;

/**
 * IF-15 on a real phone: {@code elapsedRealtimeMs()} is {@code SystemClock.elapsedRealtime()} and monotonic;
 * {@code zone()} is the current default zone.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class ClockDeviceTest {

    @Test
    public void elapsedRealtimeIsMonotonicSystemClock() {
        AndroidClock clock = new AndroidClock();
        long before = SystemClock.elapsedRealtime();
        long first = clock.elapsedRealtimeMs();
        SystemClock.sleep(20);
        long second = clock.elapsedRealtimeMs();
        assertTrue(first >= before);
        assertTrue(second - first >= 20);
    }

    @Test
    public void zoneIsCurrentDefault() {
        assertEquals(ZoneId.systemDefault(), new AndroidClock().zone());
    }
}
