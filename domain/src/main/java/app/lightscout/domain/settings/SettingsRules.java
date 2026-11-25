package app.lightscout.domain.settings;

import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.entry.ValidationField;
import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.entry.Violation;

import java.util.List;

/**
 * Settings ranges and cross-field rules (design S-04 data table; CP-00A R-3, R-30).
 *
 * <table>
 *   <caption>Valid values</caption>
 *   <tr><td>Urgent low</td><td>40–80, &lt; Low</td></tr>
 *   <tr><td>Low</td><td>&gt; Urgent low, &lt; High</td></tr>
 *   <tr><td>High</td><td>&gt; Low, ≤ 400</td></tr>
 *   <tr><td>No data</td><td>10–60 min</td></tr>
 *   <tr><td>Snoozes</td><td>5–120 min each</td></tr>
 *   <tr><td>Site / sensor lifetime</td><td>1–7 / 1–30 days</td></tr>
 *   <tr><td>Target range</td><td>From 54–140, To 120–250, From &lt; To</td></tr>
 * </table>
 */
public final class SettingsRules {

    private SettingsRules() {
    }

    /**
     * Validates a change against the current settings.
     *
     * @param current the current settings (for cross-field rules and message parameters)
     * @param c       the change
     * @return the result; parameters as documented on {@link ValidationCode}
     */
    public static ValidationResult validate(Settings current, SettingChange c) {
        int v = c.value();
        return switch (c.key()) {
            case URGENT_LOW_THRESHOLD -> check(between(v, 40, 80) && v < current.lowMgdl(),
                    ValidationCode.URGENT_LOW_RANGE, List.of(current.lowMgdl()));
            case LOW_THRESHOLD -> check(v > current.urgentLowMgdl() && v < current.highMgdl(),
                    ValidationCode.LOW_RANGE, List.of(current.urgentLowMgdl(), current.highMgdl()));
            case HIGH_THRESHOLD -> check(v > current.lowMgdl() && v <= 400,
                    ValidationCode.HIGH_RANGE, List.of(current.lowMgdl()));
            case NO_DATA_MINUTES -> check(between(v, 10, 60), ValidationCode.NO_DATA_RANGE, List.of());
            case LOW_SNOOZE, URGENT_LOW_SNOOZE, HIGH_SNOOZE, NO_DATA_SNOOZE ->
                    check(between(v, 5, 120), ValidationCode.SNOOZE_RANGE, List.of());
            case SITE_LIFETIME -> check(between(v, 1, 7), ValidationCode.SITE_LIFETIME_RANGE, List.of());
            case SENSOR_LIFETIME -> check(between(v, 1, 30), ValidationCode.SENSOR_LIFETIME_RANGE, List.of());
            case STATISTICS_CARD -> ValidationResult.ok();
            case THEME_MODE -> ValidationResult.ok();   // unknown codes read as "system" (ThemeMode.fromCode)
            case TARGET_RANGE -> {
                Integer to = c.secondValue();
                yield check(to != null && between(v, 54, 140) && between(to, 120, 250) && v < to,
                        ValidationCode.TARGET_RANGE, List.of());
            }
        };
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static ValidationResult check(boolean valid, ValidationCode code, List<Integer> params) {
        return valid ? ValidationResult.ok()
                : new ValidationResult(List.of(new Violation(ValidationField.SETTING, code, params)));
    }
}
