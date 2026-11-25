package app.lightscout.domain.entry;

/**
 * Validation result codes (CP-00A R-9). The UI maps each code to its string key; {@code :domain} has no strings.
 */
public enum ValidationCode {
    /** No (loggable) type chosen — {@code s02_helper_choose_type}. */
    TYPE_REQUIRED,
    /** BG outside 20–600 mg/dL — {@code s02_error_bg_range}. */
    BG_RANGE,
    /** Carbs outside 1–250 g — {@code s02_error_carbs_range}. */
    CARBS_RANGE,
    /** Bolus outside 0.05–25 U, not a number or more than 2 decimals — {@code s02_error_bolus_range}. */
    BOLUS_RANGE,
    /** Time more than 1 min in the future — {@code s02_error_time_future}. */
    TIME_FUTURE,
    /** Time older than 7 days — {@code s02_error_time_too_old}. */
    TIME_TOO_OLD,
    /** Urgent low threshold invalid — {@code s04_error_urgent_low}, params [low]. */
    URGENT_LOW_RANGE,
    /** Low threshold invalid — {@code s04_error_low}, params [urgentLow, high]. */
    LOW_RANGE,
    /** High threshold invalid — {@code s04_error_high}, params [low]. */
    HIGH_RANGE,
    /** No data minutes invalid — {@code s04_error_no_data}. */
    NO_DATA_RANGE,
    /** Snooze minutes invalid — {@code s04_error_snooze}. */
    SNOOZE_RANGE,
    /** Site lifetime invalid — {@code s04_error_site_lifetime}. */
    SITE_LIFETIME_RANGE,
    /** Sensor lifetime invalid — {@code s04_error_sensor_lifetime}. */
    SENSOR_LIFETIME_RANGE,
    /** Target range invalid — {@code s04_error_target_range}. */
    TARGET_RANGE
}
