package app.lightscout.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.lightscout.domain.alarm.AlarmType;

/**
 * F9 delete-intent target (CP-00A R-36, G-2; SR-20 not exported, SR-21 allow-list, SR-22): a swiped CH-01 alarm
 * notification is re-posted at once with only-alert-once. Separate from {@link SnoozeReceiver} so that a swipe can
 * never snooze (AL-4).
 */
public final class AlarmDeleteReceiver extends BroadcastReceiver {

    /** Creates the receiver (instantiated by the system). */
    public AlarmDeleteReceiver() {
        super();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlarmIntents.ACTION_NOTIFICATION_DELETED.equals(intent.getAction())) {
            return;
        }
        AlarmType type = AlarmTypeExtra.parse(intent.getStringExtra(AlarmTypeExtra.NAME));
        if (type == null) {
            return;
        }
        AlarmComponents.runtime(context).onNotificationDeleted(type);
    }
}
