package app.lightscout.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.lightscout.domain.port.EventCode;

/**
 * WF-10 time receiver (ADR-046): {@code exported="false"} (VR-20), allow-list {@code TIME_SET},
 * {@code TIMEZONE_CHANGED}; extras ignored (SR-21). If monitoring is configured: {@code TIME_CHANGED} event and a
 * {@code TIME_CHANGED} tick through {@link MonitorService}, which recomputes the derived times and re-arms tick and
 * watchdog. Armed {@code ELAPSED_REALTIME_WAKEUP} alarms are not moved by the clock jump itself (F14).
 */
public final class TimeChangeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!BootDecision.isTimeAction(action)) {
            return;
        }
        Context app = context.getApplicationContext();
        MonitorBootDependencies boot = MonitorComponents.provider(app).monitorBootDependencies();
        PendingResult pending = goAsync();
        boot.receiverExecutor().execute(() -> {
            try {
                if (BootDecision.forTime(action, boot.flags().monitoringConfigured())
                        == BootDecision.Action.TIME_CHANGED) {
                    boot.logger().event(EventCode.TIME_CHANGED);
                    MonitorComponents.launcher(app, boot).launch(StartOrigin.TIME_CHANGED);
                }
            } catch (RuntimeException e) {
                boot.logger().crash(e.getClass().getSimpleName(), "TimeChangeReceiver.onReceive");
            } finally {
                if (pending != null) {
                    pending.finish();   // null when invoked outside a broadcast (tests)
                }
            }
        });
    }
}
