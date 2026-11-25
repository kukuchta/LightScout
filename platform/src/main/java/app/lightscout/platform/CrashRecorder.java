package app.lightscout.platform;

import app.lightscout.domain.port.Logger;

/**
 * Default uncaught-exception handler that writes a one-line crash code — exception class + top app frame, never the
 * message — and then delegates to the previous handler (CP-05 §8.6, 08 §4, CP-00A R-11, R-86). No crash-reporting
 * SDK (ADR-015).
 *
 * <p>Installed first in {@code Application.onCreate} by CP-15; safe before the first unlock (the logger is a no-op
 * for the CE ring buffer while locked).
 */
public final class CrashRecorder {

    /** Package prefix that identifies an app frame (D-051). */
    static final String APP_PACKAGE_PREFIX = "app.lightscout.";

    /** Frame text when the exception has no stack trace. */
    static final String UNKNOWN_FRAME = "unknown";

    private CrashRecorder() {
    }

    /**
     * Installs the recorder as the default uncaught-exception handler, keeping the current one as delegate.
     *
     * @param logger IF-66 logger that receives {@link Logger#crash}
     */
    public static void install(Logger logger) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(handler(logger, previous));
    }

    /**
     * Creates the handler without installing it.
     *
     * @param logger   IF-66 logger
     * @param delegate handler that runs after the crash line (the system default); may be {@code null}
     * @return the handler
     */
    static Thread.UncaughtExceptionHandler handler(Logger logger, Thread.UncaughtExceptionHandler delegate) {
        return (thread, error) -> {
            try {
                logger.crash(exceptionClass(error), topAppFrame(error));
            } catch (RuntimeException ignored) {
                // the delegate must run in any case
            } finally {
                if (delegate != null) {
                    delegate.uncaughtException(thread, error);
                }
            }
        };
    }

    /**
     * Returns the simple class name of the exception.
     *
     * @param error the exception
     * @return simple class name (nested classes keep {@code $})
     */
    static String exceptionClass(Throwable error) {
        return simpleName(error.getClass().getName());
    }

    /**
     * Returns the top app frame as {@code SimpleClass.method}: the first stack frame in an {@code app.lightscout.}
     * class, else the first frame, else {@value #UNKNOWN_FRAME}.
     *
     * @param error the exception
     * @return frame text
     */
    static String topAppFrame(Throwable error) {
        StackTraceElement[] frames = error.getStackTrace();
        if (frames == null || frames.length == 0) {
            return UNKNOWN_FRAME;
        }
        StackTraceElement chosen = frames[0];
        for (StackTraceElement frame : frames) {
            if (frame.getClassName().startsWith(APP_PACKAGE_PREFIX)) {
                chosen = frame;
                break;
            }
        }
        return simpleName(chosen.getClassName()) + "." + chosen.getMethodName();
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
