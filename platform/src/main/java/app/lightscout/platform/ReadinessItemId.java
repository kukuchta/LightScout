package app.lightscout.platform;

/**
 * The S-07 readiness items 1…7 (CP-05 §8.2, CP-00A R-11). Declaration order = item number.
 */
public enum ReadinessItemId {
    /** Item 1 „Notifications”. */
    NOTIFICATIONS,
    /** Item 2 „Alarms during Do Not Disturb”. */
    DND_ACCESS,
    /** Item 3 „Full-screen alarms”. */
    FULL_SCREEN,
    /** Item 4 „Battery optimisation”. */
    BATTERY,
    /** Item 5 „Alarms &amp; reminders”. */
    EXACT_ALARM,
    /** Item 6 „Manufacturer settings” (not checkable, recommended, never counted). */
    MANUFACTURER,
    /** Item 7 „Alarm notifications”. */
    ALARM_CHANNEL
}
