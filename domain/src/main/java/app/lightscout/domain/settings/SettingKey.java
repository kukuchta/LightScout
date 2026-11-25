package app.lightscout.domain.settings;

/**
 * Settings changeable through a {@link SettingChange} (CP-00A R-3). On/off switches are not settings changes:
 * they go through IF-25 {@code AlarmActions}.
 */
public enum SettingKey {
    /** Urgent low threshold. */
    URGENT_LOW_THRESHOLD,
    /** Low threshold. */
    LOW_THRESHOLD,
    /** High threshold. */
    HIGH_THRESHOLD,
    /** No data alarm after (minutes). */
    NO_DATA_MINUTES,
    /** Low snooze (minutes). */
    LOW_SNOOZE,
    /** Urgent low snooze (minutes). */
    URGENT_LOW_SNOOZE,
    /** High snooze (minutes). */
    HIGH_SNOOZE,
    /** No data snooze (minutes). */
    NO_DATA_SNOOZE,
    /** Site lifetime (days). */
    SITE_LIFETIME,
    /** Sensor lifetime (days). */
    SENSOR_LIFETIME,
    /** Target range: {@code value} = From, {@code secondValue} = To. */
    TARGET_RANGE,
    /** Statistics card on the home screen: {@code value} 1 = shown, 0 = hidden (then a toolbar icon opens it). */
    STATISTICS_CARD,
    /** Appearance of the app screens: {@code value} = {@link ThemeMode#code()} (0 system, 1 light, 2 dark). */
    THEME_MODE
}
