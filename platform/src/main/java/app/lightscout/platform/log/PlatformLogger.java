package app.lightscout.platform.log;

import android.content.Context;

import java.io.File;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * IF-66 {@link Logger} of CMP-28 (CP-05 §8.6, CP-00A R-13; ADR-014, ADR-063, SR-07).
 *
 * <ul>
 *   <li>Every event becomes one R-13 line {@code <ISO-8601 UTC> <LEVEL> <CODE>[:<detail>]}; the detail passes the
 *       {@link LogRedaction} allow-list first.</li>
 *   <li>Logcat: the line in release and debug builds (08 §8.1, 07 §10 S2) at the priority of its level —
 *       INFO {@code Log.i}, WARN {@code Log.w}, ERROR {@code Log.e} (Q-49). {@link #debug} writes
 *       DEBUG lines in debug builds only; in release it does nothing, so release builds have no DEBUG / VERBOSE
 *       lines.</li>
 *   <li>Ring buffer: the same line, appended on a single background writer thread (no disk on the main thread)
 *       to the app-private CE file (1 MB, 7 days — {@link RingBuffer}). While the user is locked the ring buffer is
 *       a no-op: the CE file is neither resolved, created nor opened (06 §11 F6).</li>
 *   <li>{@link #crash} waits up to {@link #CRASH_WRITE_TIMEOUT_MS} for its line to be written, because the process
 *       dies right after.</li>
 *   <li>No method ever throws.</li>
 * </ul>
 */
public final class PlatformLogger implements Logger {

    /** Ring-buffer file name in the CE files directory. Not backed up (ADR-032), not exported. */
    public static final String RING_FILE_NAME = "event_log.txt";

    /** Maximum wait of {@link #crash} for its ring-buffer write. */
    public static final long CRASH_WRITE_TIMEOUT_MS = 500;

    private final Clock clock;
    private final boolean debugBuild;
    private final LogcatSink logcat;
    private final UnlockState unlockState;
    private final Supplier<LineFile> ringFile;
    private final Executor writer;
    private RingBuffer ring; // writer thread only

    /**
     * Creates the logger from its seams.
     *
     * @param clock       IF-15 clock for timestamps
     * @param debugBuild  {@code true} in debug builds ({@code BuildConfig.DEBUG} of {@code :app})
     * @param logcat      logcat output
     * @param unlockState CE availability; the ring buffer is skipped while it reports locked
     * @param ringFile    supplies the ring-buffer file; called on the writer thread only, after unlock
     * @param writer      single-threaded executor for ring-buffer writes
     */
    public PlatformLogger(Clock clock, boolean debugBuild, LogcatSink logcat, UnlockState unlockState,
                          Supplier<LineFile> ringFile, Executor writer) {
        this.clock = clock;
        this.debugBuild = debugBuild;
        this.logcat = logcat;
        this.unlockState = unlockState;
        this.ringFile = ringFile;
        this.writer = writer;
    }

    /**
     * Creates the Android logger: {@code android.util.Log}, {@code UserManager} unlock state, ring buffer
     * {@value #RING_FILE_NAME} in {@code Context.getFilesDir()} (CE, resolved only after unlock) and a daemon
     * writer thread. Safe to call before the first unlock.
     *
     * @param context    any context (the application context is kept)
     * @param clock      IF-15 clock
     * @param debugBuild {@code true} in debug builds
     * @return the logger
     */
    public static PlatformLogger create(Context context, Clock clock, boolean debugBuild) {
        Context app = context.getApplicationContext();
        ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lightscout-log");
            thread.setDaemon(true);
            return thread;
        });
        return new PlatformLogger(clock, debugBuild, new AndroidLogcatSink(), new AndroidUnlockState(app),
                () -> new FileLineFile(new File(app.getFilesDir(), RING_FILE_NAME)), writer);
    }

    @Override
    public void event(EventCode code) {
        write(code, null, false);
    }

    @Override
    public void event(EventCode code, String detail) {
        write(code, detail, false);
    }

    @Override
    public void crash(String exceptionClass, String topAppFrame) {
        write(EventCode.CRASH, LogRedaction.javaName(exceptionClass) + "@" + LogRedaction.javaName(topAppFrame), true);
    }

    @Override
    public void debug(String tag, String message) {
        if (!debugBuild) {
            return;
        }
        try {
            logcat.debug(tag, message);
        } catch (RuntimeException ignored) {
            // never throw (06 §11 F6)
        }
    }

    private void write(EventCode code, String detail, boolean crashLine) {
        try {
            if (code == null) {
                return;
            }
            Instant now = clock.now();
            String safe = crashLine ? detail : LogRedaction.detail(detail);
            String line = LogLine.format(now, code, safe);
            logcat.event(code.level(), line);
            if (unlockState.isUserUnlocked()) {
                if (crashLine) {
                    appendAndWait(line, now);
                } else {
                    writer.execute(() -> appendQuietly(line, now));
                }
            }
        } catch (RuntimeException ignored) {
            // never throw (06 §11 F6): a lost log line must not affect monitoring
        }
    }

    private void appendAndWait(String line, Instant now) {
        CountDownLatch done = new CountDownLatch(1);
        writer.execute(() -> {
            try {
                appendQuietly(line, now);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(CRASH_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void appendQuietly(String line, Instant now) {
        try {
            if (ring == null) {
                ring = new RingBuffer(ringFile.get());
            }
            ring.append(line, now);
        } catch (Exception ignored) {
            // I/O or runtime failure of the ring buffer is not logged (it would recurse) and never thrown
        }
    }
}
