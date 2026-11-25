package app.lightscout.platform.readiness;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import app.lightscout.platform.ChannelId;
import app.lightscout.platform.ChannelState;
import app.lightscout.platform.NotificationChannels;

/**
 * {@link ReadinessProbe} on {@code NotificationManager}, {@code PowerManager} and {@code AlarmManager}
 * (CP-05 §4.2). System-service queries only: no disk, no network (F15).
 */
public final class AndroidReadinessProbe implements ReadinessProbe {

    private final String packageName;
    private final NotificationManager notifications;
    private final PowerManager power;
    private final AlarmManager alarms;
    private final NotificationChannels channels;

    /**
     * Creates the probe.
     *
     * @param context  any context (the application context is used)
     * @param channels CMP-26 for the CH-01 state
     */
    public AndroidReadinessProbe(Context context, NotificationChannels channels) {
        Context app = context.getApplicationContext();
        this.packageName = app.getPackageName();
        this.notifications = app.getSystemService(NotificationManager.class);
        this.power = app.getSystemService(PowerManager.class);
        this.alarms = app.getSystemService(AlarmManager.class);
        this.channels = channels;
    }

    @Override
    public int sdkInt() {
        return Build.VERSION.SDK_INT;
    }

    @Override
    public boolean notificationsEnabled() {
        return notifications.areNotificationsEnabled();
    }

    @Override
    public boolean notificationPolicyAccessGranted() {
        return notifications.isNotificationPolicyAccessGranted();
    }

    @Override
    public boolean canUseFullScreenIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return notifications.canUseFullScreenIntent();
        }
        return true;
    }

    @Override
    public boolean ignoringBatteryOptimizations() {
        return power.isIgnoringBatteryOptimizations(packageName);
    }

    @Override
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarms.canScheduleExactAlarms();
        }
        return true;
    }

    @Override
    public ChannelState alarmChannel() {
        return channels.state(ChannelId.ALARMS);
    }
}
