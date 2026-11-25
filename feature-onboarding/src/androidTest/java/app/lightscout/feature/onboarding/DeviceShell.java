package app.lightscout.feature.onboarding;

import android.os.ParcelFileDescriptor;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.FileInputStream;
import java.io.IOException;

/** Runs a shell command through UiAutomation (font scale for TST-06 200 %); output is drained and ignored. */
final class DeviceShell {

    private DeviceShell() {
    }

    static void run(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buffer = new byte[256];
            while (in.read(buffer) >= 0) {
                // drain until the command ends
            }
        } catch (IOException e) {
            throw new IllegalStateException("shell command failed: " + command, e);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
