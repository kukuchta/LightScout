package app.lightscout;

import app.lightscout.domain.port.Logger;
import java.util.List;
import java.util.Set;

/**
 * Decides what a debug StrictMode violation does (IR-111 / IR-116, 🏛️ Q-93 + Moderator decision; CP-15 §8.2).
 *
 * <p>The violation stack is walked from the top. JDK / runtime frames ({@code java.}, {@code javax.}, {@code jdk.},
 * {@code sun.}, {@code libcore.}, {@code dalvik.} incl. BlockGuard) and the StrictMode machinery
 * ({@code android.os.StrictMode}) are skipped. The debug app is <b>killed</b> only when the first remaining frame is
 * LightScout code ({@code app.lightscout.}), or when OkHttp / Okio frames ({@code okhttp3.}, {@code okio.}) are
 * immediately followed (after skipped frames) by a LightScout frame — i.e. our code itself made the call. A framework
 * or vendor frame first ({@code android.view.}, {@code android.app.}, {@code com.miui.}, …) means the access happened
 * inside the platform, even when our lifecycle method is further down: log only (so a HyperOS framework disk read
 * never kills VS-01). VM-policy violations are fatal only for the ruled classes. Pure Java: JVM-tested.
 */
final class StrictModeVerdict {

    /** Outcome of one violation. */
    enum Action {
        /** Record the crash line, log and kill the debug process. */
        KILL,
        /** Log the violation only. */
        LOG
    }

    /** VM-policy violation classes (simple names, {@code android.os.strictmode}) that may be fatal. */
    static final Set<String> FATAL_VM_VIOLATIONS = Set.of(
            "LeakedClosableViolation",
            "IntentReceiverLeakedViolation",           // leaked registrations
            "ServiceConnectionLeakedViolation",        // leaked registrations
            "InstanceCountViolation",                  // activity leaks
            "FileUriExposedViolation",
            "ContentUriWithoutPermissionViolation",
            "UnsafeIntentLaunchViolation",
            "CredentialProtectedWhileLockedViolation",
            "ImplicitDirectBootViolation");

    private static final String APP_PREFIX = "app.lightscout.";
    /**
     * Frames skipped before deciding who caused a violation: the JDK / runtime (incl. BlockGuard), the StrictMode
     * machinery, the installer, and (BUG-04 / IR-120) the framework <b>access layer</b> that sits between a caller and
     * the disk / Keystore / TLS / package manager — so the caller of that layer decides.
     */
    private static final List<String> SKIPPED = List.of("java.", "javax.", "jdk.", "sun.", "libcore.", "dalvik.",
            "android.os.StrictMode", "app.lightscout.DebugStrictMode", "app.lightscout.StrictModeVerdict",
            "android.app.SharedPreferencesImpl", "android.app.ContextImpl", "android.content.ContextWrapper",
            "android.database.sqlite.", "android.security.", "android.os.FileUtils", "com.android.org.conscrypt.",
            "android.app.ApplicationPackageManager");
    private static final List<String> HTTP = List.of("okhttp3.", "okio.");

    private StrictModeVerdict() {
    }

    /**
     * Decides a thread-policy violation (main thread: every detected class).
     *
     * @param violation the violation (its stack is where it happened)
     * @return KILL if LightScout code made the access, else LOG
     */
    static Action forThreadPolicy(Throwable violation) {
        return causedByApp(violation) ? Action.KILL : Action.LOG;
    }

    /**
     * Decides a VM-policy violation.
     *
     * @param violation the violation
     * @return KILL for a ruled class caused by LightScout code, else LOG
     */
    static Action forVmPolicy(Throwable violation) {
        boolean fatalClass = violation != null && FATAL_VM_VIOLATIONS.contains(violation.getClass().getSimpleName());
        return fatalClass && causedByAppInChain(violation) ? Action.KILL : Action.LOG;
    }

    /**
     * VM violations (BUG-04 / IR-120 b): the violation's own stack is often only the detection site (finalizer,
     * framework); the allocation / registration / origin site is in the cause chain. The same top-of-stack rule is
     * applied to the violation and to each cause.
     *
     * @param violation the violation; {@code null} → {@code false}
     * @return {@code true} if any element of the chain was caused by LightScout code
     */
    static boolean causedByAppInChain(Throwable violation) {
        int depth = 0;
        for (Throwable t = violation; t != null && depth < 16; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (causedByApp(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes a decision (the {@code DebugStrictMode} call site; IR-117 / IR-123): for KILL the crash line is
     * written first, then {@code kill} runs; LOG does nothing here (the caller logs).
     *
     * @param action    the decision
     * @param violation the violation
     * @param logger    IF-66
     * @param kill      kills the process ({@code Process.killProcess} in the app)
     */
    static void act(Action action, Throwable violation, Logger logger, Runnable kill) {
        if (action == Action.KILL) {
            recordCrash(logger, violation);
            kill.run();
        }
    }

    /**
     * Tells whether LightScout code made the access (top-of-stack rule above).
     *
     * @param violation the violation; {@code null} → {@code false}
     * @return {@code true} if the first relevant frame is ours (directly or through OkHttp / Okio)
     */
    static boolean causedByApp(Throwable violation) {
        if (violation == null || violation.getStackTrace() == null) {
            return false;
        }
        for (StackTraceElement frame : violation.getStackTrace()) {
            String c = frame.getClassName();
            if (startsWithAny(c, SKIPPED)) {
                continue;
            }
            if (startsWithAny(c, HTTP)) {
                continue;   // OkHttp / Okio count only when our frame follows them
            }
            return c.startsWith(APP_PREFIX);
        }
        return false;
    }

    /**
     * Writes the crash line before the debug process is killed (IR-117, R-13 / 08 §8.1): same form as
     * {@code CrashRecorder} — {@code CRASH:<SimpleClass>@<Class.method>} with the first LightScout frame, no message.
     * Never throws.
     *
     * @param logger    IF-66 (its {@code crash} waits for the ring-buffer write)
     * @param violation the violation
     */
    static void recordCrash(Logger logger, Throwable violation) {
        try {
            logger.crash(violation.getClass().getSimpleName(), firstAppFrame(violation));
        } catch (RuntimeException ignored) {
            // the kill must happen anyway
        }
    }

    /**
     * Returns the first LightScout frame as {@code SimpleClass.method} (installer frames excluded), else
     * {@code unknown}.
     *
     * @param violation the violation
     * @return the frame text
     */
    static String firstAppFrame(Throwable violation) {
        StackTraceElement[] frames = violation.getStackTrace();
        if (frames != null) {
            for (StackTraceElement frame : frames) {
                String c = frame.getClassName();
                if (c.startsWith(APP_PREFIX) && !startsWithAny(c, SKIPPED)) {
                    return c.substring(c.lastIndexOf('.') + 1) + "." + frame.getMethodName();
                }
            }
        }
        return "unknown";
    }

    private static boolean startsWithAny(String className, List<String> prefixes) {
        for (String p : prefixes) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
