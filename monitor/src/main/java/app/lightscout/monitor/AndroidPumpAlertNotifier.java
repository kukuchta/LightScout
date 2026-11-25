package app.lightscout.monitor;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.port.PumpAlertNotifier;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.ui.core.AppLaunch;

/** CH-04 implementation of IF-17b. */
final class AndroidPumpAlertNotifier implements PumpAlertNotifier {

    private final Context context;
    private final NotificationChannels channels;
    private final Handler main = new Handler(Looper.getMainLooper());

    AndroidPumpAlertNotifier(Context context, NotificationChannels channels) {
        this.context = context.getApplicationContext();
        this.channels = channels;
    }

    @Override
    public void post(Entry alert) {
        if (alert == null || alert.id() == null) {
            return;
        }
        main.post(() -> postOnMain(alert));
    }

    private void postOnMain(Entry alert) {
        try {
            channels.ensureChannels();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                    channels.channelId(ChannelId.PUMP_ALERTS))
                    .setSmallIcon(app.lightscout.ui.core.R.drawable.ic_stat_lightscout)
                    .setContentTitle(context.getString(R.string.s11_pump_alert_title))
                    .setContentText(PumpAlertText.sanitize(alert.text()))
                    .setWhen(alert.time() == null ? 0L : alert.time().toEpochMilli())
                    .setShowWhen(alert.time() != null)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true);
            PendingIntent intent = contentIntent();
            if (intent != null) {
                builder.setContentIntent(intent);
            }
            NotificationManagerCompat.from(context).notify(alert.id(), NotificationIds.PUMP_ALERT, builder.build());
        } catch (SecurityException ignored) {
            // Notification permission can be denied without stopping monitoring.
        } catch (RuntimeException ignored) {
            // Includes the notification-denied path; no notification failure may stop a monitor cycle.
        }
    }

    private PendingIntent contentIntent() {
        try {
            return PendingIntent.getActivity(context, NotificationIds.PUMP_ALERT, AppLaunch.mainActivityIntent(context),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
