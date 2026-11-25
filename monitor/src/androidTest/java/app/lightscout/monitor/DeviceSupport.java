package app.lightscout.monitor;

import android.app.job.JobScheduler;
import android.content.Context;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import app.lightscout.domain.status.TickReason;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Shell, alarm-dump and clean-up helpers for the instrumented tests (target RD-2, API 33, over USB).
 */
final class DeviceSupport {

    private DeviceSupport() {
    }

    static TestMonitorApplication app() {
        return ApplicationProvider.getApplicationContext();
    }

    static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    /**
     * Runs a shell command as the shell user and waits for it.
     *
     * @param command command
     * @return stdout
     */
    static String shell(String command) {
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

    /**
     * Tells whether {@code dumpsys alarm} lists a pending alarm of this package with this action.
     *
     * @param action broadcast action
     * @return {@code true} when pending
     */
    static boolean alarmPending(String action) {
        return alarmHeader(action) != null;
    }

    /**
     * Returns the {@code dumpsys alarm} header line ({@code Alarm{...}}) of the pending alarm of this package with
     * this action (contains the type, e.g. {@code ELAPSED_WAKEUP}), or {@code null}.
     *
     * @param action broadcast action
     * @return header line
     */
    static String alarmHeader(String action) {
        return AlarmDump.pendingAlarmHeader(shell("dumpsys alarm"), context().getPackageName(),
                "*walarm*:" + action);
    }

    /** WF-05 clean-up through the production path: flag false + tick → alarms, job and service stopped. */
    static void stopMonitoring() throws InterruptedException {
        TestMonitorApplication app = app();
        app.flags.configured = false;
        app.monitorRuntime().control().requestTick(TickReason.RECONFIGURED);
        Thread.sleep(1_000);
        JobScheduler js = context().getSystemService(JobScheduler.class);
        js.cancel(AndroidJobNet.JOB_ID);
        app.flags.configured = true;
    }

    static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
