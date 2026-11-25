package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.service.notification.StatusBarNotification;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import app.lightscout.domain.status.TickReason;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * CP-08 §9 „Instrumented — startForeground first” and WF-05. Target: **RD-2 (API 33) over USB**, batched
 * (CR-04). The API 34+ runtime FGS type check is part of VR-26 on RD-1 (API 36).
 */
@RunWith(AndroidJUnit4.class)
public class MonitorServiceDeviceTest {

    private static final int ICON = app.lightscout.ui.core.R.drawable.ic_stat_lightscout;

    @After
    public void tearDown() throws InterruptedException {
        DeviceSupport.stopMonitoring();
    }

    @Test
    public void startForeground_isCalledFirst_withResourceSmallIcon() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        app.resetRuntimeRecord();   // earlier tests / tear-downs in this process already requested the runtime
        // A leftover service would make foregroundBeforeRuntime true without this start (and never re-request).
        assertTrue("no leftover MonitorService", DeviceSupport.waitUntil(() -> !MonitorService.isRunning(), 5_000));
        assertFalse("not already foreground", app.monitorServiceForeground());
        ContextCompat.startForegroundService(app, MonitorIntents.service(app, StartOrigin.TICK));
        assertTrue(DeviceSupport.waitUntil(() -> app.runtimeRequested, 5_000));
        // F4: the service was already foreground (startForeground with S-10, id 1) when the runtime / container was
        // first touched. The notification is posted asynchronously, so it is awaited separately below.
        assertTrue(app.foregroundBeforeRuntime);
        assertTrue(DeviceSupport.waitUntil(() -> app.statusNotification() != null, 5_000));
        StatusBarNotification sbn = app.statusNotification();
        assertNotNull(sbn);
        Notification n = sbn.getNotification();
        // The updater replaces the first notification (resource icon, F4) with the number icon right after startForeground, so the
        // active one may already carry the bitmap icon; the resource-icon-first rule is checked in
        // MonitorNotificationDeviceTest#s10UsesResourceIconFirstThenBitmapAndIsPublic.
        int iconType = n.getSmallIcon().getType();
        assertTrue("icon type " + iconType, iconType == android.graphics.drawable.Icon.TYPE_RESOURCE
                || iconType == android.graphics.drawable.Icon.TYPE_BITMAP);
        assertTrue((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0);
        // Q-57: no snapshot / no reading → s10_title_empty
        assertEquals("No readings", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertTrue(DeviceSupport.waitUntil(() -> app.logger.contains("FGS_START:TICK"), 5_000));
        assertTrue(app.cycle.awaitTick(TickReason.SCHEDULED, 5_000));
        // IR-71: onStartCommand returned START_STICKY (ServiceRecord: stopIfKilled=false).
        String dump = DeviceSupport.shell("dumpsys activity services " + app.getPackageName()
                + "/app.lightscout.monitor.MonitorService");
        assertTrue(dump, dump.contains("stopIfKilled=false"));
    }

    @Test
    public void wf05_onboardingDone_flagTrue_serviceStarted_ticksAndAlarmsArmed() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        // OnboardingState.completeOnboarding (CP-02) sets the DE flag, then startMonitoring(); the caller ticks.
        app.flags.setMonitoringConfigured(true);
        app.monitorRuntime().control().startMonitoring();
        app.monitorRuntime().control().requestTick(TickReason.ONBOARDING_DONE);
        assertTrue(DeviceSupport.waitUntil(MonitorService::isRunning, 5_000));
        assertTrue(app.cycle.awaitTick(TickReason.ONBOARDING_DONE, 5_000));
        assertTrue(DeviceSupport.waitUntil(() -> DeviceSupport.alarmPending(MonitorIntents.ACTION_TICK), 5_000));
        assertTrue(DeviceSupport.alarmPending(MonitorIntents.ACTION_WATCHDOG));
        String header = DeviceSupport.alarmHeader(MonitorIntents.ACTION_TICK);
        assertNotNull(header);
        assertTrue(header, header.startsWith("ELAPSED_WAKEUP") || header.contains("type 2"));   // F14
    }

    @Test
    public void wf05_connectionCleared_serviceStopped_allAlarmsAndJobCancelled() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        app.monitorRuntime().control().startMonitoring();
        app.monitorRuntime().control().requestTick(TickReason.APP_OPEN);
        assertTrue(DeviceSupport.waitUntil(() -> DeviceSupport.alarmPending(MonitorIntents.ACTION_TICK), 5_000));

        app.flags.setMonitoringConfigured(false);
        app.monitorRuntime().control().requestTick(TickReason.RECONFIGURED);
        assertTrue(DeviceSupport.waitUntil(() -> !MonitorService.isRunning(), 5_000));
        assertFalse(DeviceSupport.alarmPending(MonitorIntents.ACTION_TICK));
        assertFalse(DeviceSupport.alarmPending(MonitorIntents.ACTION_WATCHDOG));
        assertFalse(DeviceSupport.alarmPending(MonitorIntents.ACTION_HEARTBEAT));
        assertFalse(DeviceSupport.alarmPending(MonitorIntents.ACTION_BOOT_RETRY));
        assertEquals(null, DeviceSupport.context().getSystemService(android.app.job.JobScheduler.class)
                .getPendingJob(AndroidJobNet.JOB_ID));
        assertEquals(0, app.flags.ceCalls.get());
    }

    @Test
    public void fallbackS10_isPublic_silent_ongoing_withResourceSmallIcon() {
        // BUG-07 / SR-24: CH-02's PUBLIC is stored as „no override”, so the notification must be PUBLIC itself.
        Notification n = MonitorService.fallbackNotification(DeviceSupport.context(), "monitoring_v1");
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility);
        assertEquals(ICON, n.getSmallIcon().getResId());
        assertTrue((n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals("monitoring_v1", n.getChannelId());
        // DCR-06: CH-02 is DEFAULT importance — the notification itself must never alert
        assertTrue((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.getGroupAlertBehavior());   // NotificationCompat setSilent
    }
}
