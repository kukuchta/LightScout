package app.lightscout.monitor;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import app.lightscout.domain.status.TickReason;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * CP-08 §9 „Instrumented — time change” and „Instrumented — connectivity”. Target: **RD-2 (API 33) over USB**,
 * batched (CR-04). The system delivery of TIME_SET / TIMEZONE_CHANGED to the exported="false" receiver is VR-20.
 */
@RunWith(AndroidJUnit4.class)
public class TriggersDeviceTest {

    @After
    public void tearDown() throws InterruptedException {
        DeviceSupport.shell("svc wifi enable");
        DeviceSupport.shell("svc data enable");
        DeviceSupport.stopMonitoring();
    }

    @Test
    public void timeSet_andTimezoneChanged_tickAndRescheduleTickAndWatchdog() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        for (String action : new String[] {Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED}) {
            app.cycle.ticks.clear();
            new TimeChangeReceiver().onReceive(app, new Intent(action));
            assertTrue(action, app.cycle.awaitTick(TickReason.TIME_CHANGED, 10_000));
        }
        assertTrue(app.logger.contains("TIME_CHANGED"));
        assertTrue(DeviceSupport.waitUntil(() -> DeviceSupport.alarmPending(MonitorIntents.ACTION_TICK), 5_000));
        assertTrue(DeviceSupport.alarmPending(MonitorIntents.ACTION_WATCHDOG));
    }

    @Test
    public void realTimezoneChange_keepsAlarmsOnElapsedRealtime() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        app.monitorRuntime().control().startMonitoring();
        app.monitorRuntime().control().requestTick(TickReason.APP_OPEN);
        assertTrue(DeviceSupport.waitUntil(() -> DeviceSupport.alarmPending(MonitorIntents.ACTION_WATCHDOG), 5_000));
        String original = DeviceSupport.shell("getprop persist.sys.timezone").trim();
        try {
            DeviceSupport.shell("cmd alarm set-timezone Pacific/Kiritimati");
            Thread.sleep(2_000);
            String header = DeviceSupport.alarmHeader(MonitorIntents.ACTION_WATCHDOG);
            assertNotNull(header);
            assertTrue(header, header.startsWith("ELAPSED_WAKEUP") || header.contains("type 2"));   // F14
        } finally {
            DeviceSupport.shell("cmd alarm set-timezone " + (original.isEmpty() ? "Europe/Warsaw" : original));
        }
    }

    @Test
    public void connectivity_networkRegained_immediateTick() throws InterruptedException {
        TestMonitorApplication app = DeviceSupport.app();
        app.monitorRuntime();   // registers the IF-19 trigger
        DeviceSupport.shell("svc wifi disable");
        DeviceSupport.shell("svc data disable");
        Thread.sleep(10_000);   // A-08: loss surfaced ≤ 10 s (IF-19 → StatusRepository; logged NET_LOST by CMP-28)
        app.cycle.ticks.clear();
        DeviceSupport.shell("svc wifi enable");
        DeviceSupport.shell("svc data enable");
        assertTrue(app.cycle.awaitTick(TickReason.CONNECTIVITY_REGAINED, 60_000));
    }
}
