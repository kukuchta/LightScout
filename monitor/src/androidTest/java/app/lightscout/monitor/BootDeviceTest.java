package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * TST-05 (CP-08 part) — the direct-boot path in isolation: the paused notification content (verbatim, PUBLIC, no
 * actions) from a DE context; {@code LOCKED_BOOT_COMPLETED} on an unlocked device posts and writes nothing (IR-68,
 * IR-76) and never touches CE flags or the runtime (CE container). Target: **RD-2 (API 33) over USB**, batched
 * (CR-04). The real locked boot (reboot without unlock, lock screen evidence) is VR-10 on RD-1 / RD-2.
 */
@RunWith(AndroidJUnit4.class)
public class BootDeviceTest {

    private static final int ICON = app.lightscout.ui.core.R.drawable.ic_stat_lightscout;

    @Before
    public void setUp() {
        DeviceSupport.context().getSystemService(NotificationManager.class).cancel(NotificationIds.STATUS);
    }

    @After
    public void tearDown() throws InterruptedException {
        DeviceSupport.context().getSystemService(NotificationManager.class).cancel(NotificationIds.STATUS);
        DeviceSupport.stopMonitoring();
    }

    @Test
    public void pausedNotification_verbatim_publicSilent_noActions() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        Context de = app.createDeviceProtectedStorageContext();
        assertTrue(PausedNotification.post(de, app.monitorBootDependencies().channels()));
        assertTrue(DeviceSupport.waitUntil(() -> app.statusNotification() != null, 5_000));
        Notification n = app.statusNotification().getNotification();
        assertEquals("LightScout is paused", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Unlock the phone to resume LightScout",
                n.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility);
        assertEquals(0, n.actions == null ? 0 : n.actions.length);
        assertEquals(ICON, n.getSmallIcon().getResId());
    }

    @Test
    public void lockedBoot_deliveredAfterUnlock_postsNothing_writesNothing_noCeNoRuntime()
            throws InterruptedException {
        // IR-68: the test device is unlocked, so a (late) LOCKED_BOOT_COMPLETED must not overwrite S-10.
        // IR-76: LOCKED_BOOT_COMPLETED never writes the boot count. The real locked post is VR-10.
        TestMonitorApplication app = DeviceSupport.app();
        app.flags.configured = true;
        app.flags.handledBootCount = -42;
        int ceBefore = app.flags.ceCalls.get();
        boolean runtimeBefore = app.runtimeRequested;
        Context de = app.createDeviceProtectedStorageContext();
        new BootReceiver().onReceive(de, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
        Thread.sleep(1_500);
        assertEquals(null, app.statusNotification());
        assertFalse(app.logger.contains("BOOT_PAUSED"));
        assertEquals(-42, app.flags.handledBootCount);
        assertEquals(ceBefore, app.flags.ceCalls.get());
        assertEquals(runtimeBefore, app.runtimeRequested);
    }

    @Test
    public void lockedBoot_notConfigured_postsNothing() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        app.flags.configured = false;
        try {
            new BootReceiver().onReceive(app, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
            Thread.sleep(1_000);
            StatusBarNotification sbn = app.statusNotification();
            assertEquals(null, sbn);
        } finally {
            app.flags.configured = true;
        }
    }

    @Test
    public void bootCompleted_recordsBootCount_andStartsService_s10ReplacesPaused() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        PausedNotification.post(app, app.monitorBootDependencies().channels());
        assertTrue(DeviceSupport.waitUntil(() -> app.statusNotification() != null, 5_000));
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(DeviceSupport.waitUntil(MonitorService::isRunning, 5_000));
        assertTrue(DeviceSupport.waitUntil(() -> app.logger.contains("BOOT_RESUMED"), 5_000));
        Notification n = app.statusNotification().getNotification();
        assertTrue((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);   // id 1 is now S-10
        long bootCount = android.provider.Settings.Global.getInt(app.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
        assertEquals(bootCount, app.flags.handledBootCount);
    }

    @Test
    public void spoofedActions_ignored() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        new BootReceiver().onReceive(app, new Intent("android.intent.action.USER_UNLOCKED"));
        new BootReceiver().onReceive(app, new Intent("app.lightscout.FAKE").putExtra("x", 1));
        new TimeChangeReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        new WatchdogReceiver().onReceive(app, new Intent(MonitorIntents.ACTION_TICK));
        new MonitorAlarmReceiver().onReceive(app, new Intent(MonitorIntents.ACTION_WATCHDOG));
        Thread.sleep(1_000);
        assertFalse(MonitorService.isRunning());
        assertNotNull(app.logger);
        assertFalse(app.logger.contains("BOOT_PAUSED"));
    }
}
