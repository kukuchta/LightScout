package app.lightscout.monitor;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.ui.core.AppLaunch;
import app.lightscout.ui.core.Formatters;

/** CH-03 implementation of IF-17. */
final class AndroidReminderScheduler implements ReminderScheduler {

    private final Context context;
    private final NotificationChannels channels;
    private final Handler main = new Handler(Looper.getMainLooper());

    AndroidReminderScheduler(Context context, NotificationChannels channels) {
        this.context = context.getApplicationContext();
        this.channels = channels;
    }

    @Override
    public void post(ReminderNotice notice) {
        main.post(() -> postOnMain(notice));
    }

    @Override
    public void cancel(ConsumableKind kind) {
        main.post(() -> {
            try {
                NotificationManagerCompat.from(context).cancel(notificationId(kind));
            } catch (SecurityException ignored) {
                // Notification permission can be denied without stopping monitoring.
            } catch (RuntimeException ignored) {
                // Notification permission can be denied; monitoring continues.
            }
        });
    }

    private void postOnMain(ReminderNotice notice) {
        try {
            channels.ensureChannels();
            String age = Formatters.consumableAge(context, notice.age());
            ReminderNotificationContent.Content content = ReminderNotificationContent.create(notice, age,
                    new ResourceStrings());
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                    channels.channelId(ChannelId.REMINDERS))
                    .setSmallIcon(app.lightscout.ui.core.R.drawable.ic_stat_lightscout)
                    .setContentTitle(content.title())
                    .setContentText(content.text())
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(false);
            PendingIntent intent = contentIntent(notificationId(notice.kind()));
            if (intent != null) {
                builder.setContentIntent(intent);
            }
            Notification notification = builder.build();
            NotificationManagerCompat.from(context).notify(notificationId(notice.kind()), notification);
        } catch (SecurityException ignored) {
            // Notification permission can be denied without stopping monitoring.
        } catch (RuntimeException ignored) {
            // Includes the notification-denied path; no notification failure may stop a monitor cycle.
        }
    }

    private PendingIntent contentIntent(int requestCode) {
        try {
            return PendingIntent.getActivity(context, requestCode, AppLaunch.mainActivityIntent(context),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static int notificationId(ConsumableKind kind) {
        return kind == ConsumableKind.SITE ? NotificationIds.SITE_REMINDER : NotificationIds.SENSOR_REMINDER;
    }

    private final class ResourceStrings implements ReminderNotificationContent.Strings {
        @Override public String siteDueTomorrow() { return context.getString(R.string.s11_site_due_tomorrow); }
        @Override public String sensorDueTomorrow() { return context.getString(R.string.s11_sensor_due_tomorrow); }
        @Override public String siteDueNow() { return context.getString(R.string.s11_site_due_now); }
        @Override public String sensorDueNow() { return context.getString(R.string.s11_sensor_due_now); }
        @Override public String siteDueTomorrowText(String age, int days) {
            return context.getString(R.string.s11_due_tomorrow_text, age, days);
        }
        @Override public String sensorDueTomorrowText(String age, int days) {
            return context.getString(R.string.s11_sensor_due_tomorrow_text, age, days);
        }
        @Override public String siteDueNowText(String age) {
            return context.getString(R.string.s11_due_now_text, age);
        }
        @Override public String sensorDueNowText(String age) {
            return context.getString(R.string.s11_sensor_due_now_text, age);
        }
    }
}
