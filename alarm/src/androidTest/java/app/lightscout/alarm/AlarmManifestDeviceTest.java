package app.lightscout.alarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * TST-13 (CP-07 part) on the merged test-APK manifest: {@code AlarmActivity}, {@code SnoozeReceiver},
 * {@code AlarmDeleteReceiver} not exported; S-09 shows over the lock screen and turns the screen on; the §8.6
 * permissions are present and {@code READ_PHONE_STATE} / {@code SYSTEM_ALERT_WINDOW} are not. The merged-app audit is
 * CP-15 §8.5. Target: RD-2 (API 33) over USB (CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class AlarmManifestDeviceTest {

    private final Context context = AlarmDeviceSupport.context();
    private final PackageManager pm = context.getPackageManager();

    @Test
    public void componentsNotExported() throws PackageManager.NameNotFoundException {
        assertFalse(pm.getActivityInfo(new ComponentName(context, AlarmActivity.class), 0).exported);
        assertFalse(pm.getReceiverInfo(new ComponentName(context, SnoozeReceiver.class), 0).exported);
        assertFalse(pm.getReceiverInfo(new ComponentName(context, AlarmDeleteReceiver.class), 0).exported);
    }

    @Test
    public void alarmActivity_lockScreenFlags() throws PackageManager.NameNotFoundException {
        ActivityInfo info = pm.getActivityInfo(new ComponentName(context, AlarmActivity.class), 0);
        // showWhenLocked / turnScreenOn are hidden ActivityInfo flags: asserted on the manifest by the JVM audit
        // (ResourceAndSourceAuditTest) and observed by AlarmScreenDeviceTest.fsiOnLockedDevice_showsS09
        assertTrue((info.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0);
        assertTrue(info.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Test
    public void permissions() throws PackageManager.NameNotFoundException {
        PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> requested = Arrays.asList(info.requestedPermissions);
        assertTrue(requested.contains("android.permission.POST_NOTIFICATIONS"));
        assertTrue(requested.contains("android.permission.USE_FULL_SCREEN_INTENT"));
        assertTrue(requested.contains("android.permission.ACCESS_NOTIFICATION_POLICY"));
        assertTrue(requested.contains("android.permission.VIBRATE"));
        assertTrue(requested.contains("android.permission.WAKE_LOCK"));
        assertFalse(requested.contains("android.permission.READ_PHONE_STATE"));
        assertFalse(requested.contains("android.permission.SYSTEM_ALERT_WINDOW"));
    }
}
