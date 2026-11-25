package app.lightscout.platform.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import app.lightscout.domain.port.EventCode;
import app.lightscout.platform.FakeClock;

/**
 * CP-05 §9 logger rows on the JVM: TST-13 end-to-end redaction (logcat + ring buffer), release logcat (event lines at
 * the priority of their level — Q-49, no DEBUG), locked no-op for the CE ring buffer, never throws, crash line format.
 */
public class PlatformLoggerTest {

    private static final Instant T = Instant.parse("2026-10-15T08:00:00Z");

    private final FakeClock clock = new FakeClock(T);
    private final RecordingSink logcat = new RecordingSink();
    private final MemoryLineFile file = new MemoryLineFile();
    private final AtomicInteger fileResolutions = new AtomicInteger();
    private boolean unlocked = true;

    private PlatformLogger logger(boolean debugBuild, Executor writer) {
        return new PlatformLogger(clock, debugBuild, logcat, () -> unlocked, () -> {
            fileResolutions.incrementAndGet();
            return file;
        }, writer);
    }

    private PlatformLogger releaseLogger() {
        return logger(false, Runnable::run);
    }

    @Test
    public void releaseEmitsEventLineAtInfoAndWritesSameLineToRingBuffer() {
        releaseLogger().event(EventCode.SYNC_FAIL, "NO_INTERNET:NO_NETWORK");
        String expected = "2026-10-15T08:00:00.000Z WARN SYNC_FAIL:NO_INTERNET:NO_NETWORK";
        assertEquals(List.of(expected), logcat.lines);
        assertEquals(List.of(expected), file.lines);
    }

    @Test
    public void logcatPriorityFollowsLevelAndTextIsUnchanged() {
        PlatformLogger logger = releaseLogger();
        logger.event(EventCode.SYNC_OK);
        logger.event(EventCode.FGS_START_DENIED, "BOOT");
        logger.event(EventCode.STORAGE_ERROR, "readings.upsert");
        logger.crash("NullPointerException", "AlarmPlayer.start");
        assertEquals(List.of(EventCode.Level.INFO, EventCode.Level.WARN, EventCode.Level.ERROR,
                EventCode.Level.ERROR), logcat.priorities);
        assertEquals(List.of(
                "2026-10-15T08:00:00.000Z INFO SYNC_OK",
                "2026-10-15T08:00:00.000Z WARN FGS_START_DENIED:BOOT",
                "2026-10-15T08:00:00.000Z ERROR STORAGE_ERROR:readings.upsert",
                "2026-10-15T08:00:00.000Z ERROR CRASH:NullPointerException@AlarmPlayer.start"), logcat.lines);
        assertEquals(logcat.lines, file.lines);
    }

    @Test
    public void releaseHasNoDebugLines() {
        PlatformLogger logger = releaseLogger();
        logger.debug("Sync", "sgv=123 host=ns.example.com");
        logger.event(EventCode.SYNC_OK);
        assertTrue(logcat.debug.isEmpty());
        assertEquals(1, logcat.lines.size());
        assertFalse(file.lines.get(0).contains("123"));
    }

    @Test
    public void debugBuildWritesDebugToLogcatOnlyNeverToRingBuffer() {
        PlatformLogger logger = logger(true, Runnable::run);
        logger.debug("Sync", "value 123");
        assertEquals(List.of("Sync|value 123"), logcat.debug);
        assertFalse(file.touched);
    }

    @Test
    public void redactionAppliesToLogcatAndRingBuffer() {
        PlatformLogger logger = releaseLogger();
        String[] secrets = {
            "lightscout-1a2b3c4d5e6f7a8b",
            "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abc",
            "/api/v2/authorization/request/lightscout-1a2b3c4d5e6f7a8b",
            "https://ns.example.com/api/v3/entries?limit=10",
            "{\"sgv\":120}",
            "120 mg/dL",
            new java.net.ConnectException("Failed to connect to ns.example.com/203.0.113.7:443").getMessage(),
        };
        for (String secret : secrets) {
            logger.event(EventCode.SYNC_FAIL, secret);
        }
        List<String> all = new ArrayList<>(logcat.lines);
        all.addAll(file.lines);
        for (String line : all) {
            assertTrue(line, line.endsWith(" WARN SYNC_FAIL:" + LogRedaction.REDACTED));
            for (String fragment : new String[] {"lightscout-", "Bearer", "eyJ", "/api/", "example.com", "?",
                "sgv", "120", "203.0.113.7"}) {
                assertFalse(line + " contains " + fragment, line.contains(fragment));
            }
        }
    }

    @Test
    public void exceptionIsLoggedAsClassAndOutcomeOnly() {
        Exception e = new java.net.UnknownHostException("ns.example.com");
        PlatformLogger logger = releaseLogger();
        logger.event(EventCode.SYNC_FAIL, e.getMessage());                     // misuse: message → redacted
        logger.event(EventCode.SYNC_FAIL, "NO_INTERNET:NO_NETWORK");           // outcome:detail
        logger.crash(e.getClass().getSimpleName(), "NightscoutClient.get");    // class only
        assertEquals(List.of(
                "2026-10-15T08:00:00.000Z WARN SYNC_FAIL:" + LogRedaction.REDACTED,
                "2026-10-15T08:00:00.000Z WARN SYNC_FAIL:NO_INTERNET:NO_NETWORK",
                "2026-10-15T08:00:00.000Z ERROR CRASH:UnknownHostException@NightscoutClient.get"), logcat.lines);
        assertEquals(logcat.lines, file.lines);
        for (String line : logcat.lines) {
            assertFalse(line.contains("example.com"));
        }
    }

    @Test
    public void whileLockedRingBufferIsNotResolvedOrTouched() {
        unlocked = false;
        PlatformLogger logger = releaseLogger();
        logger.event(EventCode.BOOT_PAUSED);
        logger.crash("IllegalStateException", "App.onCreate");
        assertEquals(0, fileResolutions.get());
        assertFalse(file.touched);
        assertEquals(2, logcat.lines.size());
    }

    @Test
    public void afterUnlockRingBufferIsResolvedOnce() {
        unlocked = false;
        PlatformLogger logger = releaseLogger();
        logger.event(EventCode.BOOT_PAUSED);
        unlocked = true;
        logger.event(EventCode.BOOT_RESUMED);
        logger.event(EventCode.SYNC_OK);
        assertEquals(1, fileResolutions.get());
        assertEquals(2, file.lines.size());
    }

    @Test
    public void neverThrowsOnFailingFileSinkExecutorOrNullCode() {
        file.failing = true;
        logger(false, Runnable::run).event(EventCode.SYNC_OK);

        RecordingSink throwingSink = new RecordingSink();
        throwingSink.fail = true;
        new PlatformLogger(clock, true, throwingSink, () -> true, () -> file, Runnable::run).event(EventCode.SYNC_OK);
        new PlatformLogger(clock, true, throwingSink, () -> true, () -> file, Runnable::run).debug("t", "m");

        Executor rejecting = command -> {
            throw new RejectedExecutionException("shut down");
        };
        logger(false, rejecting).event(EventCode.SYNC_OK);
        logger(false, rejecting).crash("X", "Y.z");

        new PlatformLogger(clock, false, logcat, () -> {
            throw new SecurityException("locked");
        }, () -> file, Runnable::run).event(EventCode.SYNC_OK);

        releaseLogger().event(null);
        releaseLogger().event(null, "x");
    }

    @Test
    public void crashWaitsForItsRingBufferWrite() {
        Executor background = command -> new Thread(command).start();
        logger(false, background).crash("NullPointerException", "AlarmPlayer.start");
        assertEquals(1, file.lines.size());
        assertTrue(file.lines.get(0).endsWith("ERROR CRASH:NullPointerException@AlarmPlayer.start"));
    }

    @Test
    public void crashNamesWithMessageTextAreRedacted() {
        releaseLogger().crash("Boom: https://ns.example.com", "Foo.bar baz");
        assertEquals("2026-10-15T08:00:00.000Z ERROR CRASH:REDACTED@REDACTED", logcat.lines.get(0));
    }

    /** Recording logcat sink. */
    private static final class RecordingSink implements LogcatSink {
        final List<String> lines = new ArrayList<>();
        final List<EventCode.Level> priorities = new ArrayList<>();
        final List<String> debug = new ArrayList<>();
        boolean fail;

        @Override
        public void event(EventCode.Level level, String line) {
            if (fail) {
                throw new IllegalStateException("logcat broken");
            }
            priorities.add(level);
            lines.add(line);
        }

        @Override
        public void debug(String tag, String message) {
            if (fail) {
                throw new IllegalStateException("logcat broken");
            }
            debug.add(tag + "|" + message);
        }
    }
}
