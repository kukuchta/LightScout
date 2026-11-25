package app.lightscout.monitor;

/**
 * Notification ids owned by {@code :monitor} (CP-00A R-81): S-10 and „LightScout is paused” share id 1 on CH-02,
 * so S-10 replaces the paused notification.
 */
public final class NotificationIds {

    /** S-10 / „LightScout is paused” (CH-02). */
    public static final int STATUS = 1;
    /** Site reminder (CH-03). */
    public static final int SITE_REMINDER = 10;
    /** Sensor reminder (CH-03). */
    public static final int SENSOR_REMINDER = 11;
    /** Pump-alert notification id (CH-04); each alert is tagged by entry id. */
    public static final int PUMP_ALERT = 20;

    private NotificationIds() {
    }
}
