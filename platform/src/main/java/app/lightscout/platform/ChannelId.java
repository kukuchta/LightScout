package app.lightscout.platform;

/**
 * The four notification channels of CP-05 §8.1 (CP-00A R-11). No other channel exists (CP-05 §12).
 */
public enum ChannelId {
    /** CH-01 „Alarms”: glucose, No data and watchdog alarms (S-09, S-11). */
    ALARMS,
    /** CH-02 „Monitoring”: S-10 and „LightScout is paused”. */
    MONITORING,
    /** CH-03 „Reminders”: site / sensor expiry. */
    REMINDERS,
    /** CH-04 „Pump alerts”. */
    PUMP_ALERTS
}
