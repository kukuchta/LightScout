package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * TST-13 (CP-08 part) on the merged test-APK manifest: nothing of {@code :monitor} exported, {@code specialUse}
 * type + subtype property, job service protected, boot receiver direct-boot aware, the {@code :monitor} permission
 * set present. The final merged-app audit is CP-15 §8.5. Target: **RD-2 (API 33) over USB**, batched (CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class ManifestAuditDeviceTest {

    private final Context context = DeviceSupport.context();
    private final PackageManager pm = context.getPackageManager();

    @Test
    public void noMonitorComponentIsExported() throws PackageManager.NameNotFoundException {
        for (Class<?> receiver : new Class<?>[] {MonitorAlarmReceiver.class, WatchdogReceiver.class,
                BootReceiver.class, TimeChangeReceiver.class}) {
            ActivityInfo info = pm.getReceiverInfo(new ComponentName(context, receiver), 0);
            assertFalse(receiver.getSimpleName(), info.exported);
        }
        for (Class<?> service : new Class<?>[] {MonitorService.class, MonitorJobService.class}) {
            assertFalse(service.getSimpleName(), pm.getServiceInfo(new ComponentName(context, service), 0).exported);
        }
    }

    @Test
    public void monitorService_specialUse_withSubtypeProperty() throws PackageManager.NameNotFoundException {
        ComponentName cn = new ComponentName(context, MonitorService.class);
        ServiceInfo info = pm.getServiceInfo(cn, 0);
        assertEquals(0x40000000, info.getForegroundServiceType());   // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageManager.Property p = pm.getProperty("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", cn);
            assertEquals("Continuous glucose follower: monitoring and alarms", p.getString());
        }
    }

    @Test
    public void jobService_protected_bootReceiver_directBootAware() throws PackageManager.NameNotFoundException {
        ServiceInfo job = pm.getServiceInfo(new ComponentName(context, MonitorJobService.class), 0);
        assertEquals("android.permission.BIND_JOB_SERVICE", job.permission);
        ActivityInfo boot = pm.getReceiverInfo(new ComponentName(context, BootReceiver.class),
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        assertTrue(boot.directBootAware);
        ActivityInfo time = pm.getReceiverInfo(new ComponentName(context, TimeChangeReceiver.class), 0);
        assertFalse(time.directBootAware);
    }

    @Test
    public void monitorPermissionsDeclared() throws PackageManager.NameNotFoundException {
        PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> requested = Arrays.asList(info.requestedPermissions);
        for (String p : new String[] {"android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.USE_EXACT_ALARM", "android.permission.WAKE_LOCK"}) {
            assertTrue(p, requested.contains(p));
        }
        assertFalse(requested.contains("android.permission.QUERY_ALL_PACKAGES"));
        assertFalse(requested.contains("android.permission.SYSTEM_ALERT_WINDOW"));
    }
}
