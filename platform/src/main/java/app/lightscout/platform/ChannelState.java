package app.lightscout.platform;

import android.app.NotificationManager;

/**
 * Current system state of one notification channel (CP-00A R-11).
 *
 * @param currentId  the channel id in use; for {@link ChannelId#ALARMS} this may be {@code alarms_v{n}} (F7)
 * @param exists     whether the channel exists in the system
 * @param enabled    whether the channel is not blocked (importance above {@code IMPORTANCE_NONE})
 * @param importance the channel importance as reported by the system ({@code IMPORTANCE_NONE} if missing)
 * @param bypassDnd  whether the system reports {@code canBypassDnd()} for the channel
 */
public record ChannelState(String currentId, boolean exists, boolean enabled, int importance, boolean bypassDnd) {

    /**
     * Tells whether the channel can ring an alarm: enabled, importance at least HIGH and DND bypass on
     * (S-07 item 7 „Alarm notifications”, M3-32).
     *
     * @return {@code true} when item 7 is done
     */
    public boolean alarmReady() {
        return exists && enabled && importance >= NotificationManager.IMPORTANCE_HIGH && bypassDnd;
    }
}
