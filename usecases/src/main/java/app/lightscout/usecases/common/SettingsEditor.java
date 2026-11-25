package app.lightscout.usecases.common;

import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;
import app.lightscout.domain.settings.ThemeMode;

/**
 * Copy-with helpers for the immutable {@link Settings} record (on/off switches of IF-25). SettingsService
 * (CP-02b) may add the threshold / duration copies here.
 */
public final class SettingsEditor {

    private SettingsEditor() {
    }

    /**
     * Returns a copy with the „Alarms” switch changed.
     *
     * @param s  current settings
     * @param on new value
     * @return the copy
     */
    public static Settings withAlarmsEnabled(Settings s, boolean on) {
        return new Settings(on, s.lowEnabled(), s.lowMgdl(), s.urgentLowMgdl(), s.highEnabled(), s.highMgdl(),
                s.noDataMinutes(), s.lowSnoozeMin(), s.urgentLowSnoozeMin(), s.highSnoozeMin(),
                s.noDataSnoozeMin(), s.siteLifetimeDays(), s.sensorLifetimeDays(), s.targetRange(),
                s.statisticsCardOnHome(), s.themeMode());
    }

    /**
     * Returns a copy with the Low switch changed.
     *
     * @param s  current settings
     * @param on new value
     * @return the copy
     */
    public static Settings withLowEnabled(Settings s, boolean on) {
        return new Settings(s.alarmsEnabled(), on, s.lowMgdl(), s.urgentLowMgdl(), s.highEnabled(), s.highMgdl(),
                s.noDataMinutes(), s.lowSnoozeMin(), s.urgentLowSnoozeMin(), s.highSnoozeMin(),
                s.noDataSnoozeMin(), s.siteLifetimeDays(), s.sensorLifetimeDays(), s.targetRange(),
                s.statisticsCardOnHome(), s.themeMode());
    }

    /**
     * Returns a copy with the High switch changed.
     *
     * @param s  current settings
     * @param on new value
     * @return the copy
     */
    public static Settings withHighEnabled(Settings s, boolean on) {
        return new Settings(s.alarmsEnabled(), s.lowEnabled(), s.lowMgdl(), s.urgentLowMgdl(), on, s.highMgdl(),
                s.noDataMinutes(), s.lowSnoozeMin(), s.urgentLowSnoozeMin(), s.highSnoozeMin(),
                s.noDataSnoozeMin(), s.siteLifetimeDays(), s.sensorLifetimeDays(), s.targetRange(),
                s.statisticsCardOnHome(), s.themeMode());
    }

    /**
     * Returns a copy with one IF-26 setting changed (CP-02b SettingsService). The change is not validated here
     * ({@code SettingsRules.validate} is the caller's job).
     *
     * @param s current settings
     * @param c the change; {@code TARGET_RANGE} uses {@code value} as „From” and {@code secondValue} as „To”
     * @return the copy
     */
    public static Settings withChange(Settings s, SettingChange c) {
        int v = c.value();
        int ul = s.urgentLowMgdl();
        int low = s.lowMgdl();
        int high = s.highMgdl();
        int noData = s.noDataMinutes();
        int lowSnooze = s.lowSnoozeMin();
        int ulSnooze = s.urgentLowSnoozeMin();
        int highSnooze = s.highSnoozeMin();
        int noDataSnooze = s.noDataSnoozeMin();
        int site = s.siteLifetimeDays();
        int sensor = s.sensorLifetimeDays();
        TargetRange range = s.targetRange();
        boolean statisticsCard = s.statisticsCardOnHome();
        ThemeMode themeMode = s.themeMode();
        switch (c.key()) {
            case URGENT_LOW_THRESHOLD -> ul = v;
            case LOW_THRESHOLD -> low = v;
            case HIGH_THRESHOLD -> high = v;
            case NO_DATA_MINUTES -> noData = v;
            case LOW_SNOOZE -> lowSnooze = v;
            case URGENT_LOW_SNOOZE -> ulSnooze = v;
            case HIGH_SNOOZE -> highSnooze = v;
            case NO_DATA_SNOOZE -> noDataSnooze = v;
            case SITE_LIFETIME -> site = v;
            case SENSOR_LIFETIME -> sensor = v;
            case TARGET_RANGE -> range = new TargetRange(v,
                    c.secondValue() != null ? c.secondValue() : range.highMgdl());
            case STATISTICS_CARD -> statisticsCard = v != 0;
            case THEME_MODE -> themeMode = ThemeMode.fromCode(v);
            default -> throw new IllegalArgumentException("unknown setting key");
        }
        return new Settings(s.alarmsEnabled(), s.lowEnabled(), low, ul, s.highEnabled(), high, noData,
                lowSnooze, ulSnooze, highSnooze, noDataSnooze, site, sensor, range, statisticsCard, themeMode);
    }
}
