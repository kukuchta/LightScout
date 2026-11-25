package app.lightscout.domain.settings;

import app.lightscout.domain.alarm.AlarmType;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-phone settings (ENT-05, design S-04 data table, CP-00A R-3).
 *
 * @param alarmsEnabled      „Alarms” master switch (default on)
 * @param lowEnabled         Low alarm switch (default on)
 * @param lowMgdl            Low threshold (default 70)
 * @param urgentLowMgdl      Urgent low threshold, always on (default 55, DS:D-021)
 * @param highEnabled        High alarm switch (default on)
 * @param highMgdl           High threshold (default 250)
 * @param noDataMinutes      No data alarm after / data-loss threshold in minutes (default 20)
 * @param lowSnoozeMin       Low snooze minutes (default 15)
 * @param urgentLowSnoozeMin Urgent low snooze minutes (default 15)
 * @param highSnoozeMin      High snooze minutes (default 60)
 * @param noDataSnoozeMin    No data snooze minutes (default 30); also used by Not running (F20)
 * @param siteLifetimeDays   site lifetime in days (default 3)
 * @param sensorLifetimeDays sensor lifetime in days (default 10)
 * @param targetRange        statistics target range (default 70–180)
 * @param statisticsCardOnHome statistics card shown on the home screen (default on)
 * @param themeMode          appearance of the app screens (default: follow the system)
 */
public record Settings(boolean alarmsEnabled,
                       boolean lowEnabled, int lowMgdl,
                       int urgentLowMgdl,
                       boolean highEnabled, int highMgdl,
                       int noDataMinutes,
                       int lowSnoozeMin, int urgentLowSnoozeMin, int highSnoozeMin, int noDataSnoozeMin,
                       int siteLifetimeDays, int sensorLifetimeDays,
                       TargetRange targetRange, boolean statisticsCardOnHome, ThemeMode themeMode) {

    /**
     * Creates settings with the statistics card shown and the theme following the system.
     */
    public Settings(boolean alarmsEnabled,
                    boolean lowEnabled, int lowMgdl,
                    int urgentLowMgdl,
                    boolean highEnabled, int highMgdl,
                    int noDataMinutes,
                    int lowSnoozeMin, int urgentLowSnoozeMin, int highSnoozeMin, int noDataSnoozeMin,
                    int siteLifetimeDays, int sensorLifetimeDays,
                    TargetRange targetRange) {
        this(alarmsEnabled, lowEnabled, lowMgdl, urgentLowMgdl, highEnabled, highMgdl, noDataMinutes,
                lowSnoozeMin, urgentLowSnoozeMin, highSnoozeMin, noDataSnoozeMin, siteLifetimeDays,
                sensorLifetimeDays, targetRange, true, ThemeMode.SYSTEM);
    }

    /**
     * Creates settings.
     *
     * @throws NullPointerException if {@code targetRange} is null
     */
    public Settings {
        Objects.requireNonNull(targetRange, "targetRange");
        Objects.requireNonNull(themeMode, "themeMode");
    }

    /**
     * Returns the default settings of the S-04 data table.
     *
     * @return the defaults
     */
    public static Settings defaults() {
        return new Settings(true,
                true, 70,
                55,
                true, 250,
                20,
                15, 15, 60, 30,
                3, 10,
                new TargetRange(70, 180), true, ThemeMode.SYSTEM);
    }

    /**
     * Returns the snooze duration of an alarm type in minutes. {@link AlarmType#NOT_RUNNING} uses the
     * No data snooze (06 §11 F20).
     *
     * @param t the alarm type
     * @return snooze minutes
     */
    public int snoozeMinutes(AlarmType t) {
        return switch (t) {
            case URGENT_LOW -> urgentLowSnoozeMin;
            case LOW -> lowSnoozeMin;
            case HIGH -> highSnoozeMin;
            case NO_DATA, NOT_RUNNING -> noDataSnoozeMin;
        };
    }

    /**
     * Returns the data-loss threshold („No data alarm after”).
     *
     * @return {@code noDataMinutes} as a duration
     */
    public Duration dataLossThreshold() {
        return Duration.ofMinutes(noDataMinutes);
    }
}
