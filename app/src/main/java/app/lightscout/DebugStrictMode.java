package app.lightscout;

import android.os.Build;
import android.os.Process;
import android.os.StrictMode;
import android.os.strictmode.Violation;
import android.util.Log;

import app.lightscout.domain.port.Logger;

import java.util.concurrent.Executor;

/**
 * StrictMode for debug builds only (CP-15 §8.2, CP-00 §4, 03 §4, 08 §5); release builds never install it (SR-33).
 *
 * <p>Reading of „detectAll + penaltyDeath” (IR-111, 🏛️ Q-93 + Moderator decision): both policies report through
 * {@code penaltyListener}; {@link StrictModeVerdict} kills the debug process only when LightScout code itself made the
 * access (top-of-stack rule, IR-116), after writing the crash line (IR-117), and logs everything else (framework / OEM violations, e.g. HyperOS, never kill VS-01).
 * <ul>
 *   <li><b>Thread policy</b> (main thread): {@code detectAll}; any class may be fatal.</li>
 *   <li><b>VM policy</b>: fatal classes = leaked closables, leaked registrations, activity leaks, file-URI exposure,
 *       content URI without permission, unsafe intent launch, CE storage while locked, implicit direct boot;
 *       untagged sockets, non-SDK API use, cleartext, SQLite leaks and incorrect context use are logged only.</li>
 * </ul>
 */
final class DebugStrictMode {

    private static final String TAG = "LightScoutStrictMode";

    private DebugStrictMode() {
    }

    /**
     * Installs both policies on the calling (main) thread.
     *
     * @param logger IF-66, for the crash line written before a kill (IR-117)
     */
    static void install(Logger logger) {
        Executor inline = Runnable::run;
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyListener(inline, v -> handle(logger, v, StrictModeVerdict.forThreadPolicy(v)))
                .build());
        StrictMode.VmPolicy.Builder vm = new StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
                .detectCredentialProtectedWhileLocked()
                .detectImplicitDirectBoot()
                .detectLeakedSqlLiteObjects()
                .detectCleartextNetwork()
                .detectUntaggedSockets()
                .detectNonSdkApiUsage();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vm.detectUnsafeIntentLaunch().detectIncorrectContextUse();
        }
        StrictMode.setVmPolicy(vm.penaltyListener(inline,
                v -> handle(logger, v, StrictModeVerdict.forVmPolicy(v))).build());
    }

    private static void handle(Logger logger, Violation violation, StrictModeVerdict.Action action) {
        if (action == StrictModeVerdict.Action.KILL) {
            Log.e(TAG, "fatal StrictMode violation in LightScout code", violation);
        } else {
            Log.w(TAG, "StrictMode violation outside LightScout code (logged only)", violation);
        }
        // crash line in the ring buffer first, then the kill (IR-117, R-13; JVM-tested via StrictModeVerdict.act)
        StrictModeVerdict.act(action, violation, logger, () -> Process.killProcess(Process.myPid()));
    }
}
