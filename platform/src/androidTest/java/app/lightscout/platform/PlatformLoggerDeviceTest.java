package app.lightscout.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.StrictMode;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import app.lightscout.domain.port.EventCode;
import app.lightscout.platform.clock.AndroidClock;
import app.lightscout.platform.log.AndroidLogcatSink;
import app.lightscout.platform.log.FileLineFile;
import app.lightscout.platform.log.PlatformLogger;

/**
 * CP-05 §9 „Logger while locked” (TST-05 locked-start part that can run unlocked) and „no main-thread disk”.
 * The locked state is injected through {@code UnlockState}; the real before-first-unlock observation (TST-05 on
 * API 36) is a device-session step in {@code docs/implementation/vr/VR-27.md} §6 (RD-1).
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class PlatformLoggerDeviceTest {

    private File dir;
    private File ringFile;
    private ExecutorService writer;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getFilesDir(), "logger-test");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        ringFile = new File(dir, PlatformLogger.RING_FILE_NAME);
        ringFile.delete();
        writer = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws Exception {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
        ringFile.delete();
        dir.delete();
    }

    @Test
    public void whileLockedNoCeFileIsCreatedOrOpenedAndNothingThrows() throws Exception {
        List<String> resolutions = new CopyOnWriteArrayList<>();
        PlatformLogger logger = new PlatformLogger(new AndroidClock(), false, new AndroidLogcatSink(), () -> false,
                () -> {
                    resolutions.add("resolved");
                    return new FileLineFile(ringFile);
                }, writer);

        logger.event(EventCode.BOOT_PAUSED);
        logger.event(EventCode.SYNC_FAIL, "NO_INTERNET:NO_NETWORK");
        logger.crash("IllegalStateException", "App.onCreate");
        logger.debug("t", "m");
        drain();

        assertTrue(resolutions.isEmpty());
        assertFalse(ringFile.exists());
    }

    @Test
    public void afterUnlockEventsReachTheRingFile() throws Exception {
        PlatformLogger logger = new PlatformLogger(new AndroidClock(), false, new AndroidLogcatSink(), () -> true,
                () -> new FileLineFile(ringFile), writer);
        logger.event(EventCode.BOOT_RESUMED);
        logger.event(EventCode.ALARM_RING, "LOW");
        drain();

        List<String> lines = new FileLineFile(ringFile).readLines();
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith(" INFO ALARM_RING:LOW"));
    }

    @Test
    public void loggingFromMainThreadCausesNoStrictModeDiskViolation() throws Exception {
        PlatformLogger logger = new PlatformLogger(new AndroidClock(), false, new AndroidLogcatSink(), () -> true,
                () -> new FileLineFile(ringFile), writer);
        List<String> violations = new CopyOnWriteArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            StrictMode.ThreadPolicy previous = StrictMode.getThreadPolicy();
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener(Runnable::run, v -> violations.add(v.getClass().getSimpleName()))
                    .build());
            try {
                for (int i = 0; i < 20; i++) {
                    logger.event(EventCode.HEARTBEAT);
                }
            } finally {
                StrictMode.setThreadPolicy(previous);
            }
        });
        drain();
        assertTrue(violations.toString(), violations.isEmpty());
        assertEquals(20, new FileLineFile(ringFile).readLines().size());
    }

    private void drain() throws Exception {
        writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }
}
