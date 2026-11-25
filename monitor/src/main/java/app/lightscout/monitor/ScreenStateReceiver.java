package app.lightscout.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Dynamic {@code SCREEN_ON} / {@code SCREEN_OFF} receiver registered by {@link MonitorService} with
 * {@code RECEIVER_NOT_EXPORTED} (SR-23; CP-00A R-37 — owned by CP-08, shared with CP-09 inside {@code :monitor}).
 * CP-08 part: {@code SCREEN_ON} with stale data → immediate {@code SCREEN_ON_STALE} tick. CP-09 adds the minute
 * timer start ({@code SCREEN_ON}) and stop ({@code SCREEN_OFF}) here.
 */
final class ScreenStateReceiver extends BroadcastReceiver {

    private final MonitorRuntime runtime;
    private final StatusNotificationUpdater statusUpdater;

    /**
     * Creates the receiver.
     *
     * @param runtime runtime
     */
    ScreenStateReceiver(MonitorRuntime runtime, StatusNotificationUpdater statusUpdater) {
        this.runtime = runtime;
        this.statusUpdater = statusUpdater;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_SCREEN_ON.equals(action)) {
            runtime.onScreenOn();
            if (statusUpdater != null) {
                statusUpdater.screenOn();
            }
        } else if (Intent.ACTION_SCREEN_OFF.equals(action) && statusUpdater != null) {
            statusUpdater.screenOff();
        }
    }
}
