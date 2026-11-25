package app.lightscout.monitor;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import app.lightscout.platform.ChannelId;
import app.lightscout.platform.NotificationChannels;

/**
 * „LightScout is paused” (S-11 kind „Restart — waiting for unlock”, D-081; CP-08 §7): title
 * {@code s11_paused_title}, text {@code s11_paused_text}, no actions, no tap target, CH-02 (DEFAULT importance since
 * DCR-06, so HyperOS shows it on the lock screen; never audible: no channel sound or vibration, {@code setSilent},
 * alert-once), PUBLIC, id 1
 * (replaced by S-10 at the first {@code startForeground}). Direct-boot safe: string resources, the channel registry
 * and {@code NotificationManager} only (03 §6.5) — no CE storage.
 */
final class PausedNotification {

    private PausedNotification() {
    }

    /**
     * Builds the notification.
     *
     * @param context   context (DE context is fine)
     * @param channelId CH-02 id
     * @return the notification
     */
    static Notification build(Context context, String channelId) {
        return new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(app.lightscout.ui.core.R.drawable.ic_stat_lightscout)
                .setContentTitle(context.getString(R.string.s11_paused_title))
                .setContentText(context.getString(R.string.s11_paused_text))
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)   // matches CH-02 importance (DCR-06)
                .build();
    }

    /**
     * Ensures the channels and posts the notification. With notifications off nothing is visible (accepted, WF-04
     * step 1 **P**).
     *
     * @param context  context
     * @param channels CMP-26
     * @return {@code true} when {@code notify} was called without error
     */
    static boolean post(Context context, NotificationChannels channels) {
        try {
            channels.ensureChannels();
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) {
                return false;
            }
            nm.notify(NotificationIds.STATUS, build(context, channels.channelId(ChannelId.MONITORING)));
            return true;
        } catch (RuntimeException e) {
            return false;   // e.g. SecurityException without POST_NOTIFICATIONS on 33+
        }
    }
}
