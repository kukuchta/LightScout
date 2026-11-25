package app.lightscout.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;

import org.junit.Test;

/** S-04 validates the entire settings matrix using the shared domain rules before enabling Save. */
public class SettingsValidationTest {

    private final Settings defaults = Settings.defaults();

    @Test
    public void thresholdCrossFieldAndRangeLimitsAreHonored() {
        assertInvalid(SettingKey.URGENT_LOW_THRESHOLD, "81", null, ValidationCode.URGENT_LOW_RANGE);
        assertValid(SettingKey.URGENT_LOW_THRESHOLD, "40", null);
        assertInvalid(SettingKey.LOW_THRESHOLD, "40", null, ValidationCode.LOW_RANGE);
        Settings lowBoundary = new Settings(true, true, 70, 40, true, 400, 20,
                15, 15, 60, 30, 3, 10, defaults.targetRange());
        assertValid(lowBoundary, SettingKey.LOW_THRESHOLD, "41", null);
        assertValid(SettingKey.LOW_THRESHOLD, "120", null);
        assertValid(SettingKey.LOW_THRESHOLD, "121", null);
        assertValid(lowBoundary, SettingKey.LOW_THRESHOLD, "399", null);
        assertInvalid(lowBoundary, SettingKey.LOW_THRESHOLD, "400", null, ValidationCode.LOW_RANGE);
        assertValid(SettingKey.HIGH_THRESHOLD, "71", null);
        assertInvalid(SettingKey.HIGH_THRESHOLD, "70", null, ValidationCode.HIGH_RANGE);
        assertValid(SettingKey.HIGH_THRESHOLD, "400", null);
        assertInvalid(SettingKey.HIGH_THRESHOLD, "401", null, ValidationCode.HIGH_RANGE);
        Settings highLow = new Settings(true, true, 90, 55, true, 250, 20,
                15, 15, 60, 30, 3, 10, defaults.targetRange());
        assertValid(highLow, SettingKey.URGENT_LOW_THRESHOLD, "80", null);
    }

    @Test
    public void durationsAndLifetimesHonorBothBoundaries() {
        assertInvalid(SettingKey.NO_DATA_MINUTES, "9", null, ValidationCode.NO_DATA_RANGE);
        assertValid(SettingKey.NO_DATA_MINUTES, "10", null);
        assertValid(SettingKey.NO_DATA_MINUTES, "60", null);
        assertInvalid(SettingKey.NO_DATA_MINUTES, "61", null, ValidationCode.NO_DATA_RANGE);
        for (SettingKey key : new SettingKey[]{SettingKey.LOW_SNOOZE, SettingKey.URGENT_LOW_SNOOZE,
                SettingKey.HIGH_SNOOZE, SettingKey.NO_DATA_SNOOZE}) {
            assertInvalid(key, "4", null, ValidationCode.SNOOZE_RANGE);
            assertValid(key, "5", null);
            assertValid(key, "120", null);
            assertInvalid(key, "121", null, ValidationCode.SNOOZE_RANGE);
        }
        assertInvalid(SettingKey.SITE_LIFETIME, "0", null, ValidationCode.SITE_LIFETIME_RANGE);
        assertValid(SettingKey.SITE_LIFETIME, "1", null);
        assertValid(SettingKey.SITE_LIFETIME, "7", null);
        assertInvalid(SettingKey.SITE_LIFETIME, "8", null, ValidationCode.SITE_LIFETIME_RANGE);
        assertInvalid(SettingKey.SENSOR_LIFETIME, "31", null, ValidationCode.SENSOR_LIFETIME_RANGE);
        assertValid(SettingKey.SENSOR_LIFETIME, "1", null);
        assertValid(SettingKey.SENSOR_LIFETIME, "30", null);
    }

    @Test
    public void targetRangeValidatesBothFieldsAndOrder() {
        assertInvalid(SettingKey.TARGET_RANGE, "53", "180", ValidationCode.TARGET_RANGE);
        assertInvalid(SettingKey.TARGET_RANGE, "141", "180", ValidationCode.TARGET_RANGE);
        assertInvalid(SettingKey.TARGET_RANGE, "70", "119", ValidationCode.TARGET_RANGE);
        assertInvalid(SettingKey.TARGET_RANGE, "70", "251", ValidationCode.TARGET_RANGE);
        assertInvalid(SettingKey.TARGET_RANGE, "120", "120", ValidationCode.TARGET_RANGE);
        assertValid(SettingKey.TARGET_RANGE, "54", "120");
        assertValid(SettingKey.TARGET_RANGE, "140", "250");
    }

    @Test
    public void emptyAndNonNumericInputAreInvalidWithoutThrowing() {
        assertInvalid(SettingKey.NO_DATA_MINUTES, "", null, ValidationCode.NO_DATA_RANGE);
        assertInvalid(SettingKey.TARGET_RANGE, "not a number", "180", ValidationCode.TARGET_RANGE);
        assertInvalid(SettingKey.LOW_THRESHOLD, "not a number", null, ValidationCode.LOW_RANGE);
        assertInvalid(SettingKey.HIGH_THRESHOLD, "not a number", null, ValidationCode.HIGH_RANGE);
    }

    private void assertValid(SettingKey key, String value, String secondValue) {
        assertTrue(key.name(), SettingsValidation.validate(defaults, key, value, secondValue).valid());
    }

    private void assertValid(Settings current, SettingKey key, String value, String secondValue) {
        assertTrue(key.name(), SettingsValidation.validate(current, key, value, secondValue).valid());
    }

    private void assertInvalid(SettingKey key, String value, String secondValue, ValidationCode expected) {
        var result = SettingsValidation.validate(defaults, key, value, secondValue);
        assertEquals(key.name(), expected, result.violations().get(0).code());
    }

    private void assertInvalid(Settings current, SettingKey key, String value, String secondValue,
                               ValidationCode expected) {
        var result = SettingsValidation.validate(current, key, value, secondValue);
        assertEquals(key.name(), expected, result.violations().get(0).code());
    }
}
