package app.lightscout.platform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import app.lightscout.platform.connectivity.AndroidConnectivity;

/**
 * CP-05 §9 „Connectivity (instrumented)”: airplane mode on → listener {@code false}; off → {@code true}.
 * „{@code isOnline()} false on a network without validated internet” needs a captive Wi-Fi and is a device-session
 * step ({@code docs/implementation/vr/VR-27.md} §6); the validated-flag logic is covered by JVM tests.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB with a working Wi-Fi or mobile connection — batched run
 * (CP-00 §10 CR-04). Airplane mode is switched with {@code cmd connectivity airplane-mode} and always restored.
 */
@RunWith(AndroidJUnit4.class)
public class ConnectivityDeviceTest {

    private static final long TIMEOUT_S = 60;

    @After
    public void restore() {
        DeviceShell.run("cmd connectivity airplane-mode disable");
    }

    @Test
    public void airplaneModeTogglesListener() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AndroidConnectivity connectivity = new AndroidConnectivity(context, new RecordingLogger());
        assertTrue("precondition: phone online", connectivity.isOnline());
        BlockingQueue<Boolean> events = new LinkedBlockingQueue<>();
        connectivity.addListener(events::add);

        DeviceShell.run("cmd connectivity airplane-mode enable");
        assertFalse(awaitValue(events, false));
        assertFalse(connectivity.isOnline());

        DeviceShell.run("cmd connectivity airplane-mode disable");
        assertTrue(awaitValue(events, true));
        assertTrue(connectivity.isOnline());
    }

    private static boolean awaitValue(BlockingQueue<Boolean> events, boolean expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S);
        while (System.nanoTime() < deadline) {
            Boolean value = events.poll(1, TimeUnit.SECONDS);
            if (value != null && value == expected) {
                return value;
            }
        }
        throw new AssertionError("no " + expected + " event within " + TIMEOUT_S + " s");
    }
}
