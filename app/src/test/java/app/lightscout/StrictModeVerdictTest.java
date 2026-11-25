package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import app.lightscout.domain.alarm.AlarmType;
import org.junit.Test;

/** IR-111 (🏛️ Q-93 + Moderator decision): StrictMode kills only for LightScout frames; IR-109 live screen source. */
public class StrictModeVerdictTest {

    /** Stand-in violation classes named like the {@code android.os.strictmode} ones (the decision uses the name). */
    static class DiskReadViolation extends RuntimeException {
        DiskReadViolation(StackTraceElement... frames) {
            setStackTrace(frames);
        }
    }

    static class LeakedClosableViolation extends DiskReadViolation {
        LeakedClosableViolation(StackTraceElement... frames) {
            super(frames);
        }
    }

    static class UntaggedSocketViolation extends DiskReadViolation {
        UntaggedSocketViolation(StackTraceElement... frames) {
            super(frames);
        }
    }

    static class CredentialProtectedWhileLockedViolation extends DiskReadViolation {
        CredentialProtectedWhileLockedViolation(StackTraceElement... frames) {
            super(frames);
        }
    }

    private static final StackTraceElement STRICT = frame("android.os.StrictMode$AndroidBlockGuardPolicy",
            "onReadFromDisk");
    private static final StackTraceElement BLOCKGUARD = frame("libcore.io.BlockGuardOs", "access");
    private static final StackTraceElement FILE = frame("java.io.File", "exists");
    private static final StackTraceElement APP = frame("app.lightscout.storage.StorageFactory", "settings");
    private static final StackTraceElement MAIN_ON_CREATE = frame("app.lightscout.MainActivity", "onCreate");
    private static final StackTraceElement VIEW = frame("android.view.LayoutInflater", "inflate");
    private static final StackTraceElement MIUI = frame("com.miui.internal.Foo", "readConfig");
    private static final StackTraceElement CONTEXT = frame("android.app.Activity", "setContentView");
    private static final StackTraceElement OKHTTP = frame("okhttp3.internal.connection.RealCall", "execute");
    private static final StackTraceElement OKIO = frame("okio.RealBufferedSource", "read");
    private static final StackTraceElement INSTALLER = frame("app.lightscout.DebugStrictMode", "lambda$install$0");

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, "X.java", 1);
    }

    @Test
    public void ourFrameFirst_afterRuntimeFrames_kills() {
        assertEquals(StrictModeVerdict.Action.KILL, StrictModeVerdict.forThreadPolicy(
                new DiskReadViolation(STRICT, BLOCKGUARD, FILE, APP, MAIN_ON_CREATE)));
    }

    @Test
    public void vendorOrFrameworkFrameAboveOurCallback_logsOnly_ir116() {
        assertEquals("android.view above MainActivity.onCreate", StrictModeVerdict.Action.LOG,
                StrictModeVerdict.forThreadPolicy(new DiskReadViolation(STRICT, BLOCKGUARD, FILE, VIEW, MAIN_ON_CREATE)));
        assertEquals("com.miui above MainActivity.onCreate", StrictModeVerdict.Action.LOG,
                StrictModeVerdict.forThreadPolicy(new DiskReadViolation(STRICT, FILE, MIUI, MAIN_ON_CREATE)));
        assertEquals("android.app above our code", StrictModeVerdict.Action.LOG,
                StrictModeVerdict.forThreadPolicy(new DiskReadViolation(STRICT, FILE, CONTEXT, APP)));
        assertEquals(StrictModeVerdict.Action.LOG, StrictModeVerdict.forThreadPolicy(null));
        assertEquals("only runtime / installer frames", StrictModeVerdict.Action.LOG,
                StrictModeVerdict.forThreadPolicy(new DiskReadViolation(STRICT, FILE, INSTALLER)));
    }

    @Test
    public void okhttpDirectlyOverOurFrame_kills_overFramework_logs() {
        assertEquals(StrictModeVerdict.Action.KILL,
                StrictModeVerdict.forThreadPolicy(new DiskReadViolation(STRICT, OKIO, OKHTTP, FILE, APP)));
        assertEquals(StrictModeVerdict.Action.LOG,
                StrictModeVerdict.forThreadPolicy(new DiskReadViolation(STRICT, OKIO, OKHTTP, VIEW, APP)));
    }

    @Test
    public void vmPolicy_ruledClassesKillWhenCausedByUs_othersLogOnly() {
        assertEquals(StrictModeVerdict.Action.KILL,
                StrictModeVerdict.forVmPolicy(new LeakedClosableViolation(FILE, APP)));
        assertEquals(StrictModeVerdict.Action.KILL,
                StrictModeVerdict.forVmPolicy(new CredentialProtectedWhileLockedViolation(STRICT, APP)));
        assertEquals("ruled class from framework code -> log", StrictModeVerdict.Action.LOG,
                StrictModeVerdict.forVmPolicy(new LeakedClosableViolation(CONTEXT, APP)));
        assertEquals("untagged sockets (OkHttp) -> log only", StrictModeVerdict.Action.LOG,
                StrictModeVerdict.forVmPolicy(new UntaggedSocketViolation(APP)));
    }

    @Test
    public void recordCrash_writesTheCrashLineWithTheFirstLightScoutFrame_ir117() {
        TestDoubles.FakeLogger logger = new TestDoubles.FakeLogger();
        StrictModeVerdict.recordCrash(logger, new DiskReadViolation(STRICT, FILE, INSTALLER, APP, MAIN_ON_CREATE));
        assertEquals(java.util.List.of("CRASH:DiskReadViolation@StorageFactory.settings"), logger.events);
        assertEquals("unknown", StrictModeVerdict.firstAppFrame(new DiskReadViolation(STRICT, VIEW)));
    }

    @Test
    public void vmFatalList_isTheModeratorList() {
        assertEquals(java.util.Set.of("LeakedClosableViolation", "IntentReceiverLeakedViolation",
                "ServiceConnectionLeakedViolation", "InstanceCountViolation", "FileUriExposedViolation",
                "ContentUriWithoutPermissionViolation", "UnsafeIntentLaunchViolation",
                "CredentialProtectedWhileLockedViolation", "ImplicitDirectBootViolation"),
                StrictModeVerdict.FATAL_VM_VIOLATIONS);
    }

    @Test
    public void ir109_liveAlarmScreen_comesFromTheAlarmRuntimeState() {
        TestDoubles.FakeComponents c = new TestDoubles.FakeComponents(true);
        AppContainer container = new AppContainer(c);
        assertNull(container.liveAlarmScreen());
        c.liveScreen = AlarmType.URGENT_LOW;
        assertSame(AlarmType.URGENT_LOW, container.liveAlarmScreen());
        c.liveScreen = null;
        assertNull("S-09 finished after Snooze → no hand-off", container.liveAlarmScreen());
    }
}
