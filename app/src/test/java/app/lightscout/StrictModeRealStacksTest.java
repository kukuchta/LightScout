package app.lightscout;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * BUG-04 (IR-120, IR-123): {@link StrictModeVerdict} on stack shapes as reported by StrictMode on API 33 / 36 — the
 * framework access layer (SharedPreferencesImpl, ContextImpl, ContextWrapper, sqlite, Keystore, conscrypt, package
 * manager) sits between the disk / Keystore and the caller; VM leak violations carry the allocation site in the
 * cause.
 */
public class StrictModeRealStacksTest {

    /** Named like the platform classes: the decision uses the simple class name. */
    static class DiskReadViolation extends RuntimeException {
        DiskReadViolation(String... frames) {
            setStackTrace(stack(frames));
        }
    }

    static class LeakedClosableViolation extends DiskReadViolation {
        LeakedClosableViolation(Throwable allocationSite, String... frames) {
            super(frames);
            initCause(allocationSite);
        }
    }

    static class CredentialProtectedWhileLockedViolation extends DiskReadViolation {
        CredentialProtectedWhileLockedViolation(String... frames) {
            super(frames);
        }
    }

    static class ImplicitDirectBootViolation extends DiskReadViolation {
        ImplicitDirectBootViolation(String... frames) {
            super(frames);
        }
    }

    static class InstanceCountViolation extends DiskReadViolation {
        InstanceCountViolation(String... frames) {
            super(frames);
        }
    }

    private static final String[] BLOCKGUARD_READ = {
        "android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk",
        "libcore.io.BlockGuardOs.access",
        "libcore.io.ForwardingOs.access",
        "java.io.UnixFileSystem.checkAccess",
        "java.io.File.exists",
    };

    private static final String[] ACTIVITY_START = {
        "android.app.Activity.performCreate",
        "android.app.Instrumentation.callActivityOnCreate",
        "android.app.ActivityThread.performLaunchActivity",
        "android.os.Handler.dispatchMessage",
        "android.os.Looper.loop",
        "android.app.ActivityThread.main",
        "java.lang.reflect.Method.invoke",
        "com.android.internal.os.ZygoteInit.main",
    };

    private static StackTraceElement[] stack(String... frames) {
        StackTraceElement[] out = new StackTraceElement[frames.length];
        for (int i = 0; i < frames.length; i++) {
            int dot = frames[i].lastIndexOf('.');
            out[i] = new StackTraceElement(frames[i].substring(0, dot), frames[i].substring(dot + 1), "X.java", 1);
        }
        return out;
    }

    private static String[] concat(String[]... parts) {
        List<String> all = new ArrayList<>();
        for (String[] p : parts) {
            all.addAll(List.of(p));
        }
        return all.toArray(new String[0]);
    }

    private static Throwable allocationSite(String... frames) {
        Throwable t = new Throwable("Explicit termination method 'close' not called");
        t.setStackTrace(stack(frames));
        return t;
    }

    // ------------------------------------------------------------------ thread policy

    @Test
    public void ourPrefsReadOnMain_throughContextImpl_kills() {
        DiskReadViolation v = new DiskReadViolation(concat(BLOCKGUARD_READ, new String[] {
            "android.app.ContextImpl.ensurePrivateDirExists",
            "android.app.ContextImpl.getPreferencesDir",
            "android.app.ContextImpl.getSharedPreferencesPath",
            "android.app.ContextImpl.getSharedPreferences",
            "android.content.ContextWrapper.getSharedPreferences",
            "app.lightscout.storage.StorageFactory.cePrefs",
            "app.lightscout.storage.StorageFactory.settings",
            "app.lightscout.MainActivity.onCreate",
        }, ACTIVITY_START));
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forThreadPolicy(v));
    }

    @Test
    public void ourPrefsValueReadOnMain_awaitingTheLoad_kills() {
        DiskReadViolation v = new DiskReadViolation(concat(new String[] {
            "android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk",
            "android.app.SharedPreferencesImpl.awaitLoadedLocked",
            "android.app.SharedPreferencesImpl.getString",
            "app.lightscout.storage.PrefsSettingsStore.get",
            "app.lightscout.MainActivity.onResume",
        }, ACTIVITY_START));
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forThreadPolicy(v));
    }

    @Test
    public void ourSqliteOnMain_kills() {
        DiskReadViolation v = new DiskReadViolation(concat(new String[] {
            "android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk",
            "libcore.io.BlockGuardOs.open",
            "android.database.sqlite.SQLiteConnection.nativeOpen",
            "android.database.sqlite.SQLiteConnection.open",
            "android.database.sqlite.SQLiteConnectionPool.open",
            "android.database.sqlite.SQLiteDatabase.openInner",
            "android.database.sqlite.SQLiteOpenHelper.getDatabaseLocked",
            "android.database.sqlite.SQLiteOpenHelper.getReadableDatabase",
            "app.lightscout.storage.SqliteReadingStore.range",
            "app.lightscout.feature.home.HomeViewModel.load",
        }, ACTIVITY_START));
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forThreadPolicy(v));
    }

    @Test
    public void ourKeystoreOnMain_kills() {
        DiskReadViolation v = new DiskReadViolation(concat(new String[] {
            "android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk",
            "android.security.keystore2.AndroidKeyStoreSpi.engineGetKey",
            "android.security.KeyStore2.getKeyEntry",
            "java.security.KeyStore.getKey",
            "javax.crypto.Cipher.init",
            "app.lightscout.storage.KeystoreTokenCipher.decrypt",
            "app.lightscout.storage.KeystoreConnectionStore.token",
        }, ACTIVITY_START));
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forThreadPolicy(v));
    }

    @Test
    public void hyperOsFrameworkReadInsideOurOnCreate_logsOnly() {
        DiskReadViolation miui = new DiskReadViolation(concat(BLOCKGUARD_READ, new String[] {
            "java.io.FileInputStream.<init>",
            "com.miui.internal.util.DeviceConfig.read",
            "android.view.ViewRootImpl.setView",
            "android.view.WindowManagerGlobal.addView",
            "android.app.Activity.setContentView",
            "app.lightscout.MainActivity.onCreate",
        }, ACTIVITY_START));
        assertEquals(StrictModeVerdict.Action.LOG, StrictModeVerdict.forThreadPolicy(miui));
        DiskReadViolation view = new DiskReadViolation(concat(BLOCKGUARD_READ, new String[] {
            "android.content.res.ResourcesImpl.loadXmlResourceParser",
            "android.view.LayoutInflater.inflate",
            "app.lightscout.MainActivity.onCreate",
        }, ACTIVITY_START));
        assertEquals(StrictModeVerdict.Action.LOG, StrictModeVerdict.forThreadPolicy(view));
    }

    // ------------------------------------------------------------------ VM policy

    @Test
    public void leakedClosable_ourAllocationInTheCause_kills() {
        LeakedClosableViolation v = new LeakedClosableViolation(allocationSite(
                "dalvik.system.CloseGuard.open",
                "java.io.FileInputStream.<init>",
                "app.lightscout.platform.log.RingBuffer.readAll",
                "app.lightscout.platform.log.PlatformLogger.appendQuietly",
                "java.util.concurrent.ThreadPoolExecutor.runWorker",
                "java.lang.Thread.run"),
                "android.os.StrictMode.onCloseGuardLeaked",
                "dalvik.system.CloseGuard.warnIfOpen",
                "java.io.FileInputStream.finalize",
                "java.lang.Daemons$FinalizerDaemon.runInternal",
                "java.lang.Thread.run");
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forVmPolicy(v));
    }

    @Test
    public void leakedClosable_allocatedByOkHttpInternals_logsOnly() {
        LeakedClosableViolation v = new LeakedClosableViolation(allocationSite(
                "dalvik.system.CloseGuard.open",
                "com.android.org.conscrypt.ConscryptEngineSocket.<init>",
                "com.android.org.conscrypt.OpenSSLSocketFactoryImpl.createSocket",
                "okhttp3.internal.connection.ConnectPlan.connectTls",
                "okhttp3.internal.connection.ConnectPlan.connectTlsEtc",
                "okhttp3.internal.connection.FastFallbackExchangeFinder.find",
                "okhttp3.internal.concurrent.TaskRunner.runTask",
                "java.util.concurrent.ThreadPoolExecutor.runWorker",
                "java.lang.Thread.run"),
                "android.os.StrictMode.onCloseGuardLeaked",
                "dalvik.system.CloseGuard.warnIfOpen",
                "java.lang.Daemons$FinalizerDaemon.runInternal");
        assertEquals(StrictModeVerdict.Action.LOG, StrictModeVerdict.forVmPolicy(v));
    }

    @Test
    public void credentialProtectedWhileLocked_viaPrefsFromOurCode_kills() {
        CredentialProtectedWhileLockedViolation v = new CredentialProtectedWhileLockedViolation(
                "android.os.StrictMode.onCredentialProtectedPathAccess",
                "android.app.ContextImpl.getSharedPreferencesPath",
                "android.app.ContextImpl.getSharedPreferences",
                "android.content.ContextWrapper.getSharedPreferences",
                "app.lightscout.storage.StorageFactory.cePrefs",
                "app.lightscout.monitor.BootReceiver.handle",
                "java.util.concurrent.ThreadPoolExecutor.runWorker");
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forVmPolicy(v));
    }

    @Test
    public void implicitDirectBoot_viaPackageManagerFromOurCode_kills() {
        ImplicitDirectBootViolation v = new ImplicitDirectBootViolation(
                "android.os.StrictMode.onImplicitDirectBoot",
                "android.app.ApplicationPackageManager.queryIntentActivitiesAsUser",
                "android.app.ApplicationPackageManager.queryIntentActivities",
                "app.lightscout.platform.intents.AndroidSystemSettingsIntents.resolvable",
                "app.lightscout.feature.onboarding.readiness.ReadinessFragment.onResume");
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forVmPolicy(v));
    }

    @Test
    public void instanceCountWithoutStack_logsOnly() {
        assertEquals(StrictModeVerdict.Action.LOG, StrictModeVerdict.forVmPolicy(new InstanceCountViolation()));
    }

    // ------------------------------------------------------------------ IR-123 call site

    @Test
    public void act_kill_writesTheCrashLineBeforeKilling() {
        List<String> order = new ArrayList<>();
        TestDoubles.FakeLogger logger = new TestDoubles.FakeLogger() {
            @Override
            public void crash(String exceptionClass, String topAppFrame) {
                order.add("crash:" + exceptionClass + "@" + topAppFrame);
            }
        };
        DiskReadViolation v = new DiskReadViolation("android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk",
                "app.lightscout.storage.StorageFactory.settings");
        StrictModeVerdict.act(StrictModeVerdict.Action.KILL, v, logger, () -> order.add("kill"));
        assertEquals(List.of("crash:DiskReadViolation@StorageFactory.settings", "kill"), order);
    }

    @Test
    public void act_log_neitherCrashLineNorKill() {
        List<String> order = new ArrayList<>();
        TestDoubles.FakeLogger logger = new TestDoubles.FakeLogger();
        StrictModeVerdict.act(StrictModeVerdict.Action.LOG, new DiskReadViolation("android.view.View.draw"), logger,
                () -> order.add("kill"));
        assertEquals(List.of(), order);
        assertEquals(List.of(), logger.events);
    }
}
