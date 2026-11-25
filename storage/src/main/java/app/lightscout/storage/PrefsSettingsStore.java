package app.lightscout.storage;

import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;
import app.lightscout.domain.settings.ThemeMode;

/**
 * IF-11 {@link SettingsStore}: ENT-05 in the ST-02 {@code settings} preferences (CE), one key per field.
 *
 * <p>A missing key reads as its default from {@link Settings#defaults()} (CP-04 §8.3, ADR-033), so a fresh install
 * returns the defaults. {@link #put(Settings)} commits synchronously and then runs every listener once, on the
 * writer thread (ASR-17: settings apply immediately). Validation is not done here (CP-02, IF-26).
 */
final class PrefsSettingsStore implements SettingsStore {

    static final String ALARMS_ENABLED = "alarms_enabled";
    static final String LOW_ENABLED = "low_enabled";
    static final String LOW_MGDL = "low_mgdl";
    static final String URGENT_LOW_MGDL = "urgent_low_mgdl";
    static final String HIGH_ENABLED = "high_enabled";
    static final String HIGH_MGDL = "high_mgdl";
    static final String NO_DATA_MINUTES = "no_data_minutes";
    static final String LOW_SNOOZE_MIN = "low_snooze_min";
    static final String URGENT_LOW_SNOOZE_MIN = "urgent_low_snooze_min";
    static final String HIGH_SNOOZE_MIN = "high_snooze_min";
    static final String NO_DATA_SNOOZE_MIN = "no_data_snooze_min";
    static final String SITE_LIFETIME_DAYS = "site_lifetime_days";
    static final String SENSOR_LIFETIME_DAYS = "sensor_lifetime_days";
    static final String TARGET_LOW_MGDL = "target_low_mgdl";
    static final String TARGET_HIGH_MGDL = "target_high_mgdl";
    static final String STATISTICS_CARD_ON_HOME = "statistics_card_on_home";
    static final String THEME_MODE = "theme_mode";

    private static final String OP_GET = "settings.get";
    private static final String OP_PUT = "settings.put";

    private final SharedPreferences prefs;
    private final StoreGuard guard;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param prefs the CE {@code settings} preferences
     * @param guard failure logging and mapping (R-20)
     */
    PrefsSettingsStore(SharedPreferences prefs, StoreGuard guard) {
        this.prefs = prefs;
        this.guard = guard;
    }

    @Override
    public Settings get() {
        try {
            Settings d = Settings.defaults();
            return new Settings(
                    prefs.getBoolean(ALARMS_ENABLED, d.alarmsEnabled()),
                    prefs.getBoolean(LOW_ENABLED, d.lowEnabled()),
                    prefs.getInt(LOW_MGDL, d.lowMgdl()),
                    prefs.getInt(URGENT_LOW_MGDL, d.urgentLowMgdl()),
                    prefs.getBoolean(HIGH_ENABLED, d.highEnabled()),
                    prefs.getInt(HIGH_MGDL, d.highMgdl()),
                    prefs.getInt(NO_DATA_MINUTES, d.noDataMinutes()),
                    prefs.getInt(LOW_SNOOZE_MIN, d.lowSnoozeMin()),
                    prefs.getInt(URGENT_LOW_SNOOZE_MIN, d.urgentLowSnoozeMin()),
                    prefs.getInt(HIGH_SNOOZE_MIN, d.highSnoozeMin()),
                    prefs.getInt(NO_DATA_SNOOZE_MIN, d.noDataSnoozeMin()),
                    prefs.getInt(SITE_LIFETIME_DAYS, d.siteLifetimeDays()),
                    prefs.getInt(SENSOR_LIFETIME_DAYS, d.sensorLifetimeDays()),
                    new TargetRange(prefs.getInt(TARGET_LOW_MGDL, d.targetRange().lowMgdl()),
                            prefs.getInt(TARGET_HIGH_MGDL, d.targetRange().highMgdl())),
                    prefs.getBoolean(STATISTICS_CARD_ON_HOME, d.statisticsCardOnHome()),
                    ThemeMode.fromCode(prefs.getInt(THEME_MODE, d.themeMode().code())));
        } catch (RuntimeException e) {
            throw guard.fail(OP_GET, e);
        }
    }

    @Override
    public void put(Settings s) {
        boolean written;
        try {
            SharedPreferences.Editor editor = prefs.edit()
                    .putBoolean(ALARMS_ENABLED, s.alarmsEnabled())
                    .putBoolean(LOW_ENABLED, s.lowEnabled())
                    .putInt(LOW_MGDL, s.lowMgdl())
                    .putInt(URGENT_LOW_MGDL, s.urgentLowMgdl())
                    .putBoolean(HIGH_ENABLED, s.highEnabled())
                    .putInt(HIGH_MGDL, s.highMgdl())
                    .putInt(NO_DATA_MINUTES, s.noDataMinutes())
                    .putInt(LOW_SNOOZE_MIN, s.lowSnoozeMin())
                    .putInt(URGENT_LOW_SNOOZE_MIN, s.urgentLowSnoozeMin())
                    .putInt(HIGH_SNOOZE_MIN, s.highSnoozeMin())
                    .putInt(NO_DATA_SNOOZE_MIN, s.noDataSnoozeMin())
                    .putInt(SITE_LIFETIME_DAYS, s.siteLifetimeDays())
                    .putInt(SENSOR_LIFETIME_DAYS, s.sensorLifetimeDays())
                    .putInt(TARGET_LOW_MGDL, s.targetRange().lowMgdl())
                    .putInt(TARGET_HIGH_MGDL, s.targetRange().highMgdl())
                    .putBoolean(STATISTICS_CARD_ON_HOME, s.statisticsCardOnHome())
                    .putInt(THEME_MODE, s.themeMode().code());
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_PUT, e);
        }
        if (!written) {
            throw guard.fail(OP_PUT, null);
        }
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    @Override
    public void addListener(Runnable onChange) {
        listeners.add(onChange);
    }
}
