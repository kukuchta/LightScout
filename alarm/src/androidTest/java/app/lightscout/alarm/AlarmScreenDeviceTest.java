package app.lightscout.alarm;

import static app.lightscout.alarm.AlarmDeviceSupport.aliveAlarmActivities;
import static app.lightscout.alarm.AlarmDeviceSupport.app;
import static app.lightscout.alarm.AlarmDeviceSupport.context;
import static app.lightscout.alarm.AlarmDeviceSupport.finishAlarmActivities;
import static app.lightscout.alarm.AlarmDeviceSupport.glucose;
import static app.lightscout.alarm.AlarmDeviceSupport.grantNotifications;
import static app.lightscout.alarm.AlarmDeviceSupport.interactive;
import static app.lightscout.alarm.AlarmDeviceSupport.pressPower;
import static app.lightscout.alarm.AlarmDeviceSupport.resetAlarmState;
import static app.lightscout.alarm.AlarmDeviceSupport.resumedAlarmActivity;
import static app.lightscout.alarm.AlarmDeviceSupport.ring;
import static app.lightscout.alarm.AlarmDeviceSupport.runtime;
import static app.lightscout.alarm.AlarmDeviceSupport.sleepScreen;
import static app.lightscout.alarm.AlarmDeviceSupport.waitUntil;
import static app.lightscout.alarm.AlarmDeviceSupport.wakeScreen;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.model.Trend;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TST-07 S-09 on a device (CP-07 §9): FSI over the lock screen, timeout / power key never snooze (F1), S-09 re-shown at
 * the next wake (F11), volume keys snooze (A-S09-1), back does nothing (F17), no path into the app (SR-25).
 *
 * <p>Target: <b>RD-2 (API 33) over USB</b>, batched (CP-00 §10 CR-04). Preconditions: a secure screen lock is set
 * (PIN / pattern), „lock instantly with power button” on; the API 34+ FSI-permission and API 36 predictive-back parts
 * are observed on RD-1 in VR-13 / VR-17.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AlarmScreenDeviceTest {

    private static final String FSI_OP = "USE_FULL_SCREEN_INTENT";

    /** FSI app-op mode at the start of the test, restored afterwards (BUG-11). */
    private String fsiModeAtStart;

    @Before
    public void setUp() {
        grantNotifications();
        fsiModeAtStart = appOpMode(context().getPackageName(), FSI_OP);
        resetAlarmState(true);   // BUG-11: no S-09, notification, ringing state or snooze left by an earlier test
    }

    @After
    public void tearDown() {
        try {
            if (fsiModeAtStart != null) {   // first, so a throwing reset cannot leave the op at „ignore”
                shell("appops set " + context().getPackageName() + " " + FSI_OP + " " + fsiModeAtStart);
            }
        } finally {
            resetAlarmState(false);
        }
    }

    private ActivityScenario<AlarmActivity> launch(AlarmType type) {
        return ActivityScenario.launch(AlarmIntents.screen(context(), type));
    }

    @Test
    public void fsiOnLockedDevice_showsS09() {
        sleepScreen();
        assertTrue(waitUntil(() -> !interactive(), 5_000));
        KeyguardManager km = context().getSystemService(KeyguardManager.class);
        Assume.assumeTrue("secure lock screen required", km.isDeviceSecure());
        ring(glucose(AlarmType.URGENT_LOW, 52, Trend.SINGLE_DOWN));
        assertTrue("S-09 shown over the lock screen", waitUntil(() -> resumedAlarmActivity() != null, 10_000));
        assertTrue(km.isKeyguardLocked());
        assertTrue(interactive());   // turnScreenOn
    }

    @Test
    public void timeoutDoesNotSnooze_fiveMinutesUntouched() {
        shell("settings put system screen_off_timeout 15000");
        try {
            ring(glucose(AlarmType.LOW, 66, Trend.FORTY_FIVE_DOWN));
            try (ActivityScenario<AlarmActivity> s = launch(AlarmType.LOW)) {
                SystemClock.sleep(5 * 60_000L);
                assertTrue("screen still on (keep-screen-on)", interactive());
                assertNotNull("still ringing", runtime().screen().current());
                assertTrue(app().snoozes.isEmpty());
                assertNotNull(resumedAlarmActivity());
            }
        } finally {
            shell("settings put system screen_off_timeout 60000");
        }
    }

    @Test
    public void powerKeyDoesNotSnooze_s09ReshownAtNextWake() {
        ring(glucose(AlarmType.LOW, 66, Trend.FORTY_FIVE_DOWN));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.LOW)) {
            pressPower();
            assertTrue(waitUntil(() -> !interactive(), 5_000));
            SystemClock.sleep(3_000);
            assertNotNull("still ringing after the power key", runtime().screen().current());
            assertTrue(app().snoozes.isEmpty());
            wakeScreen();
            assertTrue("S-09 re-shown at the next wake", waitUntil(() -> resumedAlarmActivity() != null, 10_000));
        }
    }

    @Test
    public void volumeUp_snoozesThatType_andCloses() {
        assertVolumeKeySnoozes(KeyEvent.KEYCODE_VOLUME_UP);
    }

    @Test
    public void volumeDown_snoozesThatType_andCloses() {
        assertVolumeKeySnoozes(KeyEvent.KEYCODE_VOLUME_DOWN);
    }

    private void assertVolumeKeySnoozes(int keyCode) {
        ring(glucose(AlarmType.HIGH, 262, Trend.FORTY_FIVE_UP));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.HIGH)) {
            assertTrue(waitUntil(() -> resumedAlarmActivity() != null, 5_000));
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode);
            assertTrue(waitUntil(() -> app().snoozes.contains(AlarmType.HIGH), 5_000));
            assertTrue("S-09 closes", waitUntil(() -> resumedAlarmActivity() == null, 5_000));
            assertTrue("sound stopped (Stop applied)", waitUntil(() -> runtime().screen().current() == null, 5_000));
        }
    }

    @Test
    public void back_doesNothing_stillRinging() {
        ring(glucose(AlarmType.URGENT_LOW, 50, Trend.DOUBLE_DOWN));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.URGENT_LOW)) {
            assertTrue(waitUntil(() -> resumedAlarmActivity() != null, 5_000));
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(1_000);
            AlarmActivity a = resumedAlarmActivity();
            assertNotNull("S-09 still shown", a);
            assertFalse(a.isFinishing());
            assertNotNull("still ringing", runtime().screen().current());
            assertTrue(app().snoozes.isEmpty());
        }
    }

    @Test
    public void noPathIntoTheApp_onlySnoozeIsClickable_excludedFromRecents() throws Exception {
        ring(glucose(AlarmType.LOW, 66, Trend.FLAT));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.LOW)) {
            s.onActivity(a -> {
                View root = a.findViewById(R.id.alarm_root);
                assertEquals(1, countClickable(root));
                assertTrue(a.findViewById(R.id.alarm_snooze).isClickable());
            });
        }
        ActivityInfo info = context().getPackageManager().getActivityInfo(
                new android.content.ComponentName(context(), AlarmActivity.class), PackageManager.GET_META_DATA);
        assertTrue((info.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0);
        assertFalse(info.exported);
    }

    @Test
    public void unknownOrMissingExtra_isIgnored() {
        // ring() posts with the full-screen intent; with the screen on and unlocked HyperOS launches a valid S-09,
        // which can land after the bogus launch finished. The FSI is disabled for this test (restored in tearDown)
        // so that only the bogus instance exists, and that very instance must finish itself.
        // BUG-11: S-09 is singleInstance, so a surviving instance would get the bogus intent via onNewIntent instead
        // of a fresh instance being created → precondition „no instance”. The activity is started directly (not
        // through ActivityScenario, whose bootstrap activity cannot start behind a keyguard that an earlier test —
        // power key, lock instantly — left locked: EV-08 „PRE_ON_CREATE”); S-09 itself shows over the lock screen.
        assertTrue("precondition: no S-09 instance left", aliveAlarmActivities().isEmpty());
        shell("appops set " + context().getPackageName() + " " + FSI_OP + " ignore");
        ring(glucose(AlarmType.LOW, 66, Trend.FLAT));
        // API < 34 has no FSI app op: let a full-screen launch (if any) land and close it before the bad launch
        SystemClock.sleep(1_000);
        assertTrue(finishAlarmActivities(5_000));
        assertBadLaunchFinishesItself(
                AlarmIntents.screen(context(), AlarmType.LOW).putExtra(AlarmTypeExtra.NAME, "BOGUS"));
        Intent missing = AlarmIntents.screen(context(), AlarmType.LOW);
        missing.removeExtra(AlarmTypeExtra.NAME);
        assertBadLaunchFinishesItself(missing);
        assertNotNull("ignored, not snoozed", runtime().screen().current());
        assertTrue(app().snoozes.isEmpty());
    }

    /** Starts S-09 with an invalid intent and asserts that a new instance was created and destroyed itself. */
    private static void assertBadLaunchFinishesItself(Intent bad) {
        List<AlarmActivity> created = new CopyOnWriteArrayList<>();
        List<AlarmActivity> destroyed = new CopyOnWriteArrayList<>();
        ActivityLifecycleCallback callback = (activity, stage) -> {
            if (activity instanceof AlarmActivity alarm) {
                if (stage == Stage.CREATED) {
                    created.add(alarm);
                } else if (stage == Stage.DESTROYED) {
                    destroyed.add(alarm);
                }
            }
        };
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback);
        try {
            context().startActivity(bad);   // NEW_TASK is set by AlarmIntents.screen
            assertTrue("bad S-09 launch creates an instance", waitUntil(() -> !created.isEmpty(), 5_000));
            assertTrue("bogus S-09 instance finishes", waitUntil(() -> destroyed.containsAll(created), 5_000));
            assertTrue("no S-09 instance left", aliveAlarmActivities().isEmpty());
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
        }
    }

    /**
     * Reads the current mode of an app op ({@code appops get} prints e.g. {@code OP: allow; time=...}).
     *
     * @return the mode, or {@code default} when none is set or the op is unknown (API &lt; 34)
     */
    private static String appOpMode(String pkg, String op) {
        String out = AlarmDeviceSupport.shell("appops get " + pkg + " " + op);
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith(op + ":")) {
                String mode = t.substring(op.length() + 1).trim().split("[;\\s]")[0];
                if (!mode.isEmpty()) {
                    return mode;
                }
            }
        }
        return "default";
    }

    private static int countClickable(View v) {
        int n = v.isClickable() && v.getVisibility() == View.VISIBLE ? 1 : 0;
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                n += countClickable(g.getChildAt(i));
            }
        }
        return n;
    }

    private static void shell(String cmd) {
        AlarmDeviceSupport.shell(cmd);
    }
}
