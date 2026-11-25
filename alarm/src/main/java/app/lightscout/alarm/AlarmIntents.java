package app.lightscout.alarm;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import app.lightscout.domain.alarm.AlarmType;

/**
 * Explicit intents and immutable {@code PendingIntent}s of the alarm surfaces (IF-50 content, IF-51 Snooze action,
 * IF-52 full-screen intent, F9 delete intent; SR-22, CP-00A R-36): explicit component + package,
 * {@code FLAG_IMMUTABLE}, a unique request code per purpose and {@code alarmType}, extra {@code alarmType}.
 * No trampolines: the content and full-screen intents start {@link AlarmActivity} directly.
 */
final class AlarmIntents {

    /** {@link SnoozeReceiver} action (R-36 „…action.SNOOZE”). */
    static final String ACTION_SNOOZE = "app.lightscout.alarm.action.SNOOZE";

    /** {@link AlarmDeleteReceiver} action (R-36 „…action.ALARM_NOTIFICATION_DELETED”). */
    static final String ACTION_NOTIFICATION_DELETED = "app.lightscout.alarm.action.ALARM_NOTIFICATION_DELETED";

    /** {@link AlarmActivity} action. */
    static final String ACTION_SHOW = "app.lightscout.alarm.action.SHOW";

    /**
     * Purpose of a {@code PendingIntent}; the base of its request code.
     */
    enum Purpose {
        /** IF-50 content intent → S-09. */
        CONTENT(100),
        /** IF-52 full-screen intent → S-09. */
        FULL_SCREEN(200),
        /** IF-51 Snooze action → {@link SnoozeReceiver}. */
        SNOOZE(300),
        /** F9 delete intent → {@link AlarmDeleteReceiver}. */
        DELETE(400);

        private final int base;

        Purpose(int base) {
            this.base = base;
        }
    }

    private AlarmIntents() {
    }

    /**
     * Returns the unique request code of a purpose and type (SR-22).
     *
     * @param purpose purpose
     * @param type    alarm type
     * @return request code
     */
    static int requestCode(Purpose purpose, AlarmType type) {
        return purpose.base + type.ordinal();
    }

    /**
     * Explicit intent that opens S-09 for a type.
     *
     * @param context context
     * @param type    alarm type
     * @return the intent (new task: {@link AlarmActivity} runs in its own task, ADR-022)
     */
    static Intent screen(Context context, AlarmType type) {
        Intent i = new Intent(context, AlarmActivity.class)
                .setAction(ACTION_SHOW)
                .setPackage(context.getPackageName())
                .putExtra(AlarmTypeExtra.NAME, type.name());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * IF-50 / IF-52 activity {@code PendingIntent}.
     *
     * @param context context
     * @param purpose {@link Purpose#CONTENT} or {@link Purpose#FULL_SCREEN}
     * @param type    alarm type
     * @return the immutable pending intent
     */
    static PendingIntent screenPending(Context context, Purpose purpose, AlarmType type) {
        return PendingIntent.getActivity(context, requestCode(purpose, type), screen(context, type),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * IF-51 Snooze action {@code PendingIntent}.
     *
     * @param context context
     * @param type    alarm type
     * @return the immutable pending intent
     */
    static PendingIntent snoozePending(Context context, AlarmType type) {
        Intent i = new Intent(context, SnoozeReceiver.class)
                .setAction(ACTION_SNOOZE)
                .setPackage(context.getPackageName())
                .putExtra(AlarmTypeExtra.NAME, type.name());
        return PendingIntent.getBroadcast(context, requestCode(Purpose.SNOOZE, type), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * F9 delete-intent {@code PendingIntent}.
     *
     * @param context context
     * @param type    alarm type
     * @return the immutable pending intent
     */
    static PendingIntent deletePending(Context context, AlarmType type) {
        Intent i = new Intent(context, AlarmDeleteReceiver.class)
                .setAction(ACTION_NOTIFICATION_DELETED)
                .setPackage(context.getPackageName())
                .putExtra(AlarmTypeExtra.NAME, type.name());
        return PendingIntent.getBroadcast(context, requestCode(Purpose.DELETE, type), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
