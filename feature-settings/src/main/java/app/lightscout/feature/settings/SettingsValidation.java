package app.lightscout.feature.settings;

import android.content.Context;

import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.SettingsRules;
import app.lightscout.domain.entry.Violation;

/** S-04 UI mapping for the shared, string-free domain validation result. */
final class SettingsValidation {

    private SettingsValidation() {
    }

    static ValidationResult validate(Settings current, SettingKey key, String value, String secondValue) {
        int first = parse(value);
        Integer second = key == SettingKey.TARGET_RANGE ? parse(secondValue) : null;
        return SettingsRules.validate(current, new SettingChange(key, first, second));
    }

    static String message(Context context, ValidationResult result) {
        if (result == null || result.valid()) {
            return null;
        }
        Violation violation = result.violations().get(0);
        ValidationCode code = violation.code();
        return switch (code) {
            case URGENT_LOW_RANGE -> context.getString(R.string.s04_error_urgent_low,
                    violation.params().get(0));
            case LOW_RANGE -> context.getString(R.string.s04_error_low,
                    violation.params().get(0), violation.params().get(1));
            case HIGH_RANGE -> context.getString(R.string.s04_error_high,
                    violation.params().get(0));
            case NO_DATA_RANGE -> context.getString(R.string.s04_error_no_data);
            case SNOOZE_RANGE -> context.getString(R.string.s04_error_snooze);
            case SITE_LIFETIME_RANGE -> context.getString(R.string.s04_error_site_lifetime);
            case SENSOR_LIFETIME_RANGE -> context.getString(R.string.s04_error_sensor_lifetime);
            case TARGET_RANGE -> context.getString(R.string.s04_error_target_range);
            default -> throw new IllegalArgumentException("Not an S-04 validation code: " + code);
        };
    }

    private static int parse(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
