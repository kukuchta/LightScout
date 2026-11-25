package app.lightscout.usecases;

import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.Settings;

import java.util.function.Consumer;

/**
 * CMP-16 SettingsService — IF-26 (CP-02 §8.8, CP-00A R-30): validated settings, applied immediately to monitoring
 * and alarms (ASR-17). On/off switches go through IF-25 {@link AlarmActions}, not through this interface.
 */
public interface SettingsService {

    /**
     * Returns the current settings. Blocking (prefs): call off the main thread (R-34).
     *
     * @return the settings
     */
    Settings get();

    /**
     * Validates a change with {@code SettingsRules.validate}; only a valid change is persisted, then
     * {@code MonitorControl.reevaluateAlarms()} is requested. Blocking: call off the main thread (R-34).
     *
     * @param c the change
     * @return the validation result (codes + parameters, no strings)
     */
    ValidationResult update(SettingChange c);

    /**
     * Registers a listener told about every stored settings change; it fires on the writer thread.
     *
     * @param l the listener
     */
    void addListener(Consumer<Settings> l);

    /**
     * Removes a listener.
     *
     * @param l the listener
     */
    void removeListener(Consumer<Settings> l);
}
