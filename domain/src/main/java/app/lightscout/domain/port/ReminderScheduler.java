package app.lightscout.domain.port;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderNotice;

/**
 * Reminder notification port (IF-17), implemented by {@code :monitor}.
 */
public interface ReminderScheduler {

    /**
     * Posts a reminder.
     *
     * @param n the notice
     */
    void post(ReminderNotice n);

    /**
     * Cancels the reminder of a consumable.
     *
     * @param k consumable
     */
    void cancel(ConsumableKind k);
}
