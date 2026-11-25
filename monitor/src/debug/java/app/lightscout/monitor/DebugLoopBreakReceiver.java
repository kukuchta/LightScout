package app.lightscout.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * **Debug builds only** (`src/debug`; 🏛️ Q-63 / IR-66). VR-16 Run B: makes the sync loop dead while the process
 * lives, so the watchdog → NOT_RUNNING path can be verified on a device. Exported in the debug manifest only and
 * protected by {@code android.permission.DUMP}, so only {@code adb shell} can send it. Extras are ignored.
 *
 * <ul>
 *   <li>{@code app.lightscout.monitor.debug.BREAK_LOOP}: suppress tick re-arming and cancel the armed tick.</li>
 *   <li>{@code app.lightscout.monitor.debug.RESTORE_LOOP}: lift the suppression and start a tick.</li>
 * </ul>
 */
public final class DebugLoopBreakReceiver extends BroadcastReceiver {

    /** Breaks the loop. */
    static final String ACTION_BREAK = "app.lightscout.monitor.debug.BREAK_LOOP";
    /** Restores the loop. */
    static final String ACTION_RESTORE = "app.lightscout.monitor.debug.RESTORE_LOOP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_BREAK.equals(action)) {
            LoopBreakSwitch.set(true);
            new AndroidAlarmPort(context).cancel(AlarmPort.Kind.TICK);
        } else if (ACTION_RESTORE.equals(action)) {
            LoopBreakSwitch.set(false);
            MonitorBootDependencies boot = MonitorComponents.provider(context).monitorBootDependencies();
            TickWakeLock.onAlarmReceipt(context);
            MonitorComponents.launcher(context, boot).launch(StartOrigin.TICK);
        }
    }
}
