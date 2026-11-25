package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;

import android.widget.Button;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * §9 "GIR-1 guard": a double tap on a guarded button triggers the action once; after completion it is accepted
 * again.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04). Compiled in SS-08. Taps are
 * {@code performClick()} on the main thread (no activity needed); the feature-level Espresso run belongs to the
 * screens using the guard.
 */
@RunWith(AndroidJUnit4.class)
public class GuardDeviceTest {

    @Test
    public void doubleTapRunsOnceThenAcceptsAgainAfterCompletion() {
        AtomicInteger runs = new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Button button = new Button(DeviceContexts.themed(false));
            SingleActionGuard guard = new SingleActionGuard();
            guard.attach(button, runs::incrementAndGet);
            button.performClick();
            button.performClick();
            assertEquals(1, runs.get());
            guard.complete();
            button.performClick();
            assertEquals(2, runs.get());
        });
    }
}
