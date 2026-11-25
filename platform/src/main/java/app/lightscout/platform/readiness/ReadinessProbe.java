package app.lightscout.platform.readiness;

import app.lightscout.platform.ChannelState;

/**
 * SDK-level / capability seam of {@link DefaultReadinessChecker}: the system queries behind S-07 items 1–7
 * (CP-05 §4.2, §8.2). Band-gated queries are called only on the band where the API exists.
 */
public interface ReadinessProbe {

    /**
     * Returns the API level of the phone.
     *
     * @return {@code Build.VERSION.SDK_INT}
     */
    int sdkInt();

    /**
     * Item 1: {@code areNotificationsEnabled()}.
     *
     * @return {@code true} when the app may post notifications
     */
    boolean notificationsEnabled();

    /**
     * Item 2: {@code NotificationManager.isNotificationPolicyAccessGranted()} (PERM-10).
     *
     * @return {@code true} when granted
     */
    boolean notificationPolicyAccessGranted();

    /**
     * Item 3: {@code NotificationManager.canUseFullScreenIntent()}. Called only when {@link #sdkInt()} ≥ 34.
     *
     * @return {@code true} when allowed
     */
    boolean canUseFullScreenIntent();

    /**
     * Item 4: {@code PowerManager.isIgnoringBatteryOptimizations(packageName)} (PERM-09).
     *
     * @return {@code true} when the app is exempt
     */
    boolean ignoringBatteryOptimizations();

    /**
     * Item 5: {@code AlarmManager.canScheduleExactAlarms()}. Called only when {@link #sdkInt()} ≥ 31.
     *
     * @return {@code true} when allowed
     */
    boolean canScheduleExactAlarms();

    /**
     * Item 7: state of CH-01.
     *
     * @return the alarm channel state
     */
    ChannelState alarmChannel();
}
