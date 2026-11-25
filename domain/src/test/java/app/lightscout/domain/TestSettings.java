package app.lightscout.domain;

import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;

/**
 * Test builder for {@link Settings}, starting from the §9 defaults (UL 55 / Low 70 / High 250 / No data 20 /
 * snooze 15·15·60·30).
 */
public final class TestSettings {

    private boolean alarmsEnabled = true;
    private boolean lowEnabled = true;
    private boolean highEnabled = true;
    private int noDataSnoozeMin = 30;
    private int noDataMinutes = 20;

    /**
     * Returns a builder with the defaults.
     *
     * @return builder
     */
    public static TestSettings defaults() {
        return new TestSettings();
    }

    /**
     * Sets the „Alarms” switch.
     *
     * @param v value
     * @return this
     */
    public TestSettings alarms(boolean v) {
        alarmsEnabled = v;
        return this;
    }

    /**
     * Sets the Low switch.
     *
     * @param v value
     * @return this
     */
    public TestSettings low(boolean v) {
        lowEnabled = v;
        return this;
    }

    /**
     * Sets the High switch.
     *
     * @param v value
     * @return this
     */
    public TestSettings high(boolean v) {
        highEnabled = v;
        return this;
    }

    /**
     * Sets the No data snooze.
     *
     * @param v minutes
     * @return this
     */
    public TestSettings noDataSnooze(int v) {
        noDataSnoozeMin = v;
        return this;
    }

    /**
     * Sets the No data threshold.
     *
     * @param v minutes
     * @return this
     */
    public TestSettings noDataMinutes(int v) {
        noDataMinutes = v;
        return this;
    }

    /**
     * Builds the settings.
     *
     * @return settings
     */
    public Settings build() {
        return new Settings(alarmsEnabled, lowEnabled, 70, 55, highEnabled, 250, noDataMinutes,
                15, 15, 60, noDataSnoozeMin, 3, 10, new TargetRange(70, 180));
    }
}
