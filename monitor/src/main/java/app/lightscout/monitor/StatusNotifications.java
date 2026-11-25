package app.lightscout.monitor;

import android.content.Context;

import app.lightscout.domain.port.Clock;
import app.lightscout.platform.NotificationChannels;

/**
 * Factory of the S-10 builder used by {@code MonitorService} (CP-00A R-81). CP-09 changes this one method to
 * return the full S-10 builder; nothing else in CP-08 depends on the implementation.
 */
final class StatusNotifications {

    private StatusNotifications() {
    }

    /**
     * Returns the S-10 builder.
     *
     * @param context  context
     * @param channels CMP-26 (CH-02 id)
     * @param clock    IF-15
     * @return the S-10 builder
     */
    static StatusNotificationBuilder builder(Context context, NotificationChannels channels, Clock clock) {
        return new PersistentStatusNotificationBuilder(context, channels, clock);
    }
}
