package app.lightscout.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * CMP-52 {@code WatchdogReceiver} (ADR-045, F2, F3): target of the watchdog exact alarm, not exported (SR-20),
 * allow-list {@code …action.WATCHDOG} (SR-21).
 *
 * <p>It **never rings itself** (F3, CP-08 §12): it re-arms the watchdog at {@code now + 5 min} first (so a refused
 * start retries), takes the sync wake lock and starts {@link MonitorService} (exact-alarm exemption). The service
 * then checks staleness, evaluates (NOT_RUNNING through IF-01 → IF-16) and ticks ({@link TickRunner#runWatchdog}).
 */
public final class WatchdogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !MonitorIntents.ACTION_WATCHDOG.equals(intent.getAction())) {
            return;
        }
        MonitorBootDependencies boot = MonitorComponents.provider(context).monitorBootDependencies();
        MonitorAlarms alarms = MonitorComponents.alarms(context, boot);
        alarms.armWatchdog(new WatchdogSchedule().afterFire(boot.clock().now(), null));
        TickWakeLock.onAlarmReceipt(context);
        new ServiceLauncher(new AndroidServiceStarter(context), alarms, boot.logger()).launch(StartOrigin.WATCHDOG);
    }
}
