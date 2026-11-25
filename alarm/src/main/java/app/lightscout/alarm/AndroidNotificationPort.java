package app.lightscout.alarm;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.port.Clock;

/**
 * CH-01 alarm notification (C-S11-01, C-S11-02; ADR-047, IF-50…IF-52, F9, SR-24; CP-00A R-81 id 2): category
 * {@code ALARM}, ongoing, PUBLIC on the lock screen, full-screen intent → S-09, content intent → S-09, the single
 * action „Snooze {n} min”, delete intent → re-post. No sound or vibration from the notification: the channel is
 * silent and no defaults are set — sound and vibration come from {@code AlarmPlayer} (USAGE_ALARM).
 *
 * <p>Posting hops to the main looper (03 §4 „hop to the main looper only to touch notifications”). With
 * notifications denied the system drops the post; sound and vibration continue (WF-02 step 2).
 */
final class AndroidNotificationPort implements AlarmSurfaceController.NotificationPort {

    /** Alarm notification id (CP-00A R-81: one alarm notification at a time). */
    static final int NOTIFICATION_ID = 2;

    private final Context context;
    private final Clock clock;
    private final AlarmTextRenderer texts;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Creates the port.
     *
     * @param context application context
     * @param clock   IF-15
     */
    AndroidNotificationPort(Context context, Clock clock) {
        this.context = context;
        this.clock = clock;
        this.texts = new AlarmTextRenderer(context);
    }

    @Override
    public void post(String channelId, AlarmView view, boolean onlyAlertOnce) {
        main.post(() -> {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, build(channelId, view, onlyAlertOnce));
            }
        });
    }

    @Override
    public void cancel() {
        main.post(() -> {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        });
    }

    private Notification build(String channelId, AlarmView view, boolean onlyAlertOnce) {
        String action = texts.snoozeAction(view);
        return new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(app.lightscout.ui.core.R.drawable.ic_stat_lightscout)
                .setContentTitle(texts.notificationTitle(view, clock.now()))
                .setContentText(texts.notificationText(view, clock.zone()))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(onlyAlertOnce)
                .setContentIntent(AlarmIntents.screenPending(context, AlarmIntents.Purpose.CONTENT, view.type()))
                .setFullScreenIntent(
                        AlarmIntents.screenPending(context, AlarmIntents.Purpose.FULL_SCREEN, view.type()), true)
                .setDeleteIntent(AlarmIntents.deletePending(context, view.type()))
                .addAction(new NotificationCompat.Action.Builder(null, action,
                        AlarmIntents.snoozePending(context, view.type())).build())
                .build();
    }
}
