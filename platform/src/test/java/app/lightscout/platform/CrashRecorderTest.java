package app.lightscout.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * CP-05 §9 „Crash code”: an uncaught exception writes one line (class + top app frame, no message) and then the
 * previous default handler runs (08 §4, R-86).
 */
public class CrashRecorderTest {

    private final Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();

    @After
    public void restore() {
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    @Test
    public void writesOneLineThenDelegates() {
        RecordingLogger logger = new RecordingLogger();
        List<String> order = new ArrayList<>();
        Thread.UncaughtExceptionHandler delegate = (t, e) -> order.add("delegate:" + logger.events.size());
        Throwable error = errorWithFrames("https://ns.example.com/api?token=secret",
                new StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 1),
                new StackTraceElement("app.lightscout.monitor.MonitorService", "onTick", "MonitorService.java", 2),
                new StackTraceElement("app.lightscout.usecases.Other", "run", "Other.java", 3));

        CrashRecorder.handler(logger, delegate).uncaughtException(Thread.currentThread(), error);

        assertEquals(List.of("CRASH:IllegalStateException@MonitorService.onTick"), logger.events);
        assertEquals(List.of("delegate:1"), order);
    }

    @Test
    public void messageNeverReachesTheLogger() {
        RecordingLogger logger = new RecordingLogger();
        Throwable error = errorWithFrames("Failed to connect to ns.example.com",
                new StackTraceElement("app.lightscout.nightscout.Client", "get", "Client.java", 9));
        CrashRecorder.handler(logger, null).uncaughtException(Thread.currentThread(), error);
        for (String event : logger.events) {
            assertTrue(!event.contains("example.com") && !event.contains("Failed"));
        }
    }

    @Test
    public void withoutAppFrameUsesFirstFrame() {
        Throwable error = errorWithFrames("x", new StackTraceElement("android.os.Looper", "loop", "Looper.java", 1));
        assertEquals("Looper.loop", CrashRecorder.topAppFrame(error));
    }

    @Test
    public void withoutStackUsesUnknown() {
        Throwable error = errorWithFrames("x");
        assertEquals(CrashRecorder.UNKNOWN_FRAME, CrashRecorder.topAppFrame(error));
    }

    @Test
    public void nestedClassKeepsDollar() {
        Throwable error = errorWithFrames("x",
                new StackTraceElement("app.lightscout.alarm.AlarmPlayer$Loop", "run", "AlarmPlayer.java", 1));
        assertEquals("AlarmPlayer$Loop.run", CrashRecorder.topAppFrame(error));
        assertEquals("IllegalStateException", CrashRecorder.exceptionClass(error));
    }

    @Test
    public void failingLoggerStillDelegates() {
        List<Throwable> delegated = new ArrayList<>();
        Logger throwing = new Logger() {
            @Override
            public void event(EventCode code) {
            }

            @Override
            public void event(EventCode code, String detail) {
            }

            @Override
            public void crash(String exceptionClass, String topAppFrame) {
                throw new IllegalStateException("logger broken");
            }

            @Override
            public void debug(String tag, String message) {
            }
        };
        Throwable error = errorWithFrames("x");
        CrashRecorder.handler(throwing, (t, e) -> delegated.add(e)).uncaughtException(Thread.currentThread(), error);
        assertEquals(1, delegated.size());
        assertSame(error, delegated.get(0));
    }

    @Test
    public void installKeepsPreviousHandlerAsDelegate() {
        List<Throwable> delegated = new ArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> delegated.add(e));
        RecordingLogger logger = new RecordingLogger();
        CrashRecorder.install(logger);

        Throwable error = errorWithFrames("x", new StackTraceElement("app.lightscout.App", "onCreate", "App.java", 1));
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), error);

        assertEquals(List.of("CRASH:IllegalStateException@App.onCreate"), logger.events);
        assertEquals(1, delegated.size());
    }

    private static Throwable errorWithFrames(String message, StackTraceElement... frames) {
        IllegalStateException error = new IllegalStateException(message);
        error.setStackTrace(frames);
        return error;
    }
}
