package app.lightscout.platform;

import android.os.ParcelFileDescriptor;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Runs a shell command as the shell user through {@code UiAutomation} and waits for it to finish.
 */
final class DeviceShell {

    private DeviceShell() {
    }

    /**
     * Runs a command.
     *
     * @param command shell command
     * @return its standard output
     */
    static String run(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            pfd.close();
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
