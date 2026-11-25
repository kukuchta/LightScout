package app.lightscout.monitor;

import android.content.Context;

import app.lightscout.domain.port.PumpAlertNotifier;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.platform.NotificationChannels;

/** Public CP-09 factories used by the :app composition root. */
public final class MonitorNotifications {

    private MonitorNotifications() {
    }

    public static ReminderScheduler reminderScheduler(Context context, NotificationChannels channels) {
        return new AndroidReminderScheduler(context, channels);
    }

    public static PumpAlertNotifier pumpAlertNotifier(Context context, NotificationChannels channels) {
        return new AndroidPumpAlertNotifier(context, channels);
    }
}
