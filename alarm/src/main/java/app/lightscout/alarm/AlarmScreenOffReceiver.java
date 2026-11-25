package app.lightscout.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/**
 * Dynamic {@code SCREEN_OFF} receiver of {@code :alarm} (F11; CP-00A R-36, R-37): registered with
 * {@code RECEIVER_NOT_EXPORTED} only while an alarm rings; no dependency on {@code :monitor}.
 */
final class AlarmScreenOffReceiver extends BroadcastReceiver implements AlarmSurfaceController.ScreenOffWatch {

    private final Context context;
    private Runnable onScreenOff;
    private boolean registered;

    /**
     * Creates the receiver.
     *
     * @param context application context
     */
    AlarmScreenOffReceiver(Context context) {
        this.context = context;
    }

    @Override
    public synchronized void register(Runnable callback) {
        onScreenOff = callback;
        if (!registered) {
            ContextCompat.registerReceiver(context, this, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
    }

    @Override
    public synchronized void unregister() {
        if (registered) {
            context.unregisterReceiver(this);
            registered = false;
        }
        onScreenOff = null;
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            return;
        }
        Runnable r;
        synchronized (this) {
            r = onScreenOff;
        }
        if (r != null) {
            r.run();
        }
    }
}
