package app.lightscout.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import app.lightscout.domain.alarm.AlarmType;

/**
 * CMP-43 SnoozeReceiver (IF-51, A-S11-1; SR-20 not exported, SR-21 action allow-list, SR-22 validated
 * {@code alarmType}): the CH-01 action „Snooze {n} min”, also from the lock screen. Anything other than
 * {@code …action.SNOOZE} with a known {@code alarmType} is ignored.
 */
public final class SnoozeReceiver extends BroadcastReceiver {

    /** Creates the receiver (instantiated by the system). */
    public SnoozeReceiver() {
        super();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AlarmIntents.ACTION_SNOOZE.equals(intent.getAction())) {
            return;
        }
        AlarmType type = AlarmTypeExtra.parse(intent.getStringExtra(AlarmTypeExtra.NAME));
        if (type == null) {
            return;
        }
        AlarmComponents.runtime(context).requestSnooze(type);
    }
}
