package app.lightscout.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Target of the tick, heartbeat and boot-retry exact alarms (CP-00A R-36), not exported (SR-20). Allow-list:
 * {@code …action.TICK}, {@code …action.HEARTBEAT}, {@code …action.BOOT_RETRY}; anything else is ignored (SR-21).
 *
 * <p>WF-01 step 1: short wake lock (≤ 30 s) → start {@link MonitorService} (exact-alarm FGS start exemption, F4);
 * the service enqueues the work on {@code monitorExecutor}. A refused start → {@code FGS_START_DENIED} + retry
 * alarm ({@link ServiceLauncher}). Uses the direct-boot-safe dependencies only (no container build on main).
 */
public final class MonitorAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        StartOrigin origin = originFor(intent != null ? intent.getAction() : null);
        if (origin == null) {
            return;
        }
        MonitorBootDependencies boot = MonitorComponents.provider(context).monitorBootDependencies();
        TickWakeLock.onAlarmReceipt(context);
        MonitorComponents.launcher(context, boot).launch(origin);
    }

    /**
     * Maps an action to its start origin.
     *
     * @param action action (nullable)
     * @return origin, or {@code null} when not on the allow-list
     */
    static StartOrigin originFor(String action) {
        if (MonitorIntents.ACTION_TICK.equals(action)) {
            return StartOrigin.TICK;
        }
        if (MonitorIntents.ACTION_HEARTBEAT.equals(action)) {
            return StartOrigin.HEARTBEAT;
        }
        if (MonitorIntents.ACTION_BOOT_RETRY.equals(action)) {
            return StartOrigin.BOOT_RETRY;
        }
        return null;
    }
}
