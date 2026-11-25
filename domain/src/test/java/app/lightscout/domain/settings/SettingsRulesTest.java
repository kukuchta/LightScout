package app.lightscout.domain.settings;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.entry.ValidationField;
import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.entry.Violation;

import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * TST-01 settings defaults and A-21 ranges / cross-field rules (CP-01 §4.1.5; CP-00A R-3).
 */
public class SettingsRulesTest {

    @Test
    public void statisticsCardIsShownByDefaultAndByTheCompatibilityConstructor() {
        Settings d = Settings.defaults();
        assertTrue(d.statisticsCardOnHome());
        Settings legacy = new Settings(d.alarmsEnabled(), d.lowEnabled(), d.lowMgdl(), d.urgentLowMgdl(),
                d.highEnabled(), d.highMgdl(), d.noDataMinutes(), d.lowSnoozeMin(), d.urgentLowSnoozeMin(),
                d.highSnoozeMin(), d.noDataSnoozeMin(), d.siteLifetimeDays(), d.sensorLifetimeDays(),
                d.targetRange());
        assertTrue(legacy.statisticsCardOnHome());
    }

    private final Settings defaults = Settings.defaults();

    private ValidationResult check(SettingKey key, int value) {
        return SettingsRules.validate(defaults, new SettingChange(key, value, null));
    }

    private static void assertInvalid(ValidationResult r, ValidationCode code, List<Integer> params) {
        assertEquals(List.of(new Violation(ValidationField.SETTING, code, params)), r.violations());
    }

    private void assertRange(SettingKey key, int min, int max, ValidationCode code) {
        assertInvalid(check(key, min - 1), code, List.of());
        assertTrue(check(key, min).valid());
        assertTrue(check(key, max).valid());
        assertInvalid(check(key, max + 1), code, List.of());
    }

    @Test
    public void defaults_matchS04DataTable() {
        Settings s = Settings.defaults();
        assertTrue(s.alarmsEnabled());
        assertTrue(s.lowEnabled());
        assertTrue(s.highEnabled());
        assertEquals(70, s.lowMgdl());
        assertEquals(55, s.urgentLowMgdl());
        assertEquals(250, s.highMgdl());
        assertEquals(20, s.noDataMinutes());
        assertEquals(15, s.lowSnoozeMin());
        assertEquals(15, s.urgentLowSnoozeMin());
        assertEquals(60, s.highSnoozeMin());
        assertEquals(30, s.noDataSnoozeMin());
        assertEquals(3, s.siteLifetimeDays());
        assertEquals(10, s.sensorLifetimeDays());
        assertEquals(new TargetRange(70, 180), s.targetRange());
        assertEquals(Duration.ofMinutes(20), s.dataLossThreshold());
    }

    @Test
    public void f20_snoozeMinutesPerType_notRunningUsesNoData() {
        assertEquals(15, defaults.snoozeMinutes(AlarmType.URGENT_LOW));
        assertEquals(15, defaults.snoozeMinutes(AlarmType.LOW));
        assertEquals(60, defaults.snoozeMinutes(AlarmType.HIGH));
        assertEquals(30, defaults.snoozeMinutes(AlarmType.NO_DATA));
        assertEquals(30, defaults.snoozeMinutes(AlarmType.NOT_RUNNING));
    }

    @Test
    public void urgentLow_40to80_belowLow() {
        assertInvalid(check(SettingKey.URGENT_LOW_THRESHOLD, 39), ValidationCode.URGENT_LOW_RANGE, List.of(70));
        assertTrue(check(SettingKey.URGENT_LOW_THRESHOLD, 40).valid());
        assertTrue(check(SettingKey.URGENT_LOW_THRESHOLD, 69).valid());
        assertInvalid(check(SettingKey.URGENT_LOW_THRESHOLD, 70), ValidationCode.URGENT_LOW_RANGE, List.of(70));
        Settings highLow = new Settings(true, true, 100, 55, true, 250, 20, 15, 15, 60, 30, 3, 10,
                new TargetRange(70, 180));
        assertTrue(SettingsRules.validate(highLow, new SettingChange(SettingKey.URGENT_LOW_THRESHOLD, 80, null)).valid());
        assertInvalid(SettingsRules.validate(highLow, new SettingChange(SettingKey.URGENT_LOW_THRESHOLD, 81, null)),
                ValidationCode.URGENT_LOW_RANGE, List.of(100));
    }

    @Test
    public void low_aboveUrgentLow_belowHigh_withoutFixedBounds() {
        List<Integer> params = List.of(55, 250);
        assertInvalid(check(SettingKey.LOW_THRESHOLD, 55), ValidationCode.LOW_RANGE, params);   // not > urgent low
        assertTrue(check(SettingKey.LOW_THRESHOLD, 56).valid());
        assertTrue(check(SettingKey.LOW_THRESHOLD, 120).valid());
        assertTrue(check(SettingKey.LOW_THRESHOLD, 121).valid());
        Settings lowUrgent = new Settings(true, true, 70, 40, true, 250, 20, 15, 15, 60, 30, 3, 10,
                new TargetRange(70, 180));
        assertTrue(SettingsRules.validate(lowUrgent, new SettingChange(SettingKey.LOW_THRESHOLD, 41, null)).valid());
        Settings highLimit = new Settings(true, true, 70, 40, true, 400, 20, 15, 15, 60, 30, 3, 10,
                new TargetRange(70, 180));
        assertTrue(SettingsRules.validate(highLimit, new SettingChange(SettingKey.LOW_THRESHOLD, 399, null)).valid());
        assertInvalid(SettingsRules.validate(highLimit, new SettingChange(SettingKey.LOW_THRESHOLD, 400, null)),
                ValidationCode.LOW_RANGE, List.of(40, 400));
    }

    @Test
    public void high_aboveLow_through400_withoutFixedMinimum() {
        assertTrue(check(SettingKey.HIGH_THRESHOLD, 71).valid());
        assertInvalid(check(SettingKey.HIGH_THRESHOLD, 70), ValidationCode.HIGH_RANGE, List.of(70));
        assertTrue(check(SettingKey.HIGH_THRESHOLD, 400).valid());
        assertInvalid(check(SettingKey.HIGH_THRESHOLD, 401), ValidationCode.HIGH_RANGE, List.of(70));
        Settings low120 = new Settings(true, true, 120, 55, true, 250, 20, 15, 15, 60, 30, 3, 10,
                new TargetRange(70, 180));
        assertInvalid(SettingsRules.validate(low120, new SettingChange(SettingKey.HIGH_THRESHOLD, 120, null)),
                ValidationCode.HIGH_RANGE, List.of(120));
    }

    @Test
    public void noData_snoozes_lifetimes() {
        assertRange(SettingKey.NO_DATA_MINUTES, 10, 60, ValidationCode.NO_DATA_RANGE);
        assertRange(SettingKey.LOW_SNOOZE, 5, 120, ValidationCode.SNOOZE_RANGE);
        assertRange(SettingKey.URGENT_LOW_SNOOZE, 5, 120, ValidationCode.SNOOZE_RANGE);
        assertRange(SettingKey.HIGH_SNOOZE, 5, 120, ValidationCode.SNOOZE_RANGE);
        assertRange(SettingKey.NO_DATA_SNOOZE, 5, 120, ValidationCode.SNOOZE_RANGE);
        assertRange(SettingKey.SITE_LIFETIME, 1, 7, ValidationCode.SITE_LIFETIME_RANGE);
        assertRange(SettingKey.SENSOR_LIFETIME, 1, 30, ValidationCode.SENSOR_LIFETIME_RANGE);
    }

    @Test
    public void targetRange_from54to140_to120to250_fromBelowTo() {
        assertTrue(range(54, 250).valid());
        assertTrue(range(140, 141).valid());
        assertTrue(range(70, 120).valid());
        assertInvalid(range(53, 180), ValidationCode.TARGET_RANGE, List.of());
        assertInvalid(range(141, 180), ValidationCode.TARGET_RANGE, List.of());
        assertInvalid(range(70, 119), ValidationCode.TARGET_RANGE, List.of());
        assertInvalid(range(70, 251), ValidationCode.TARGET_RANGE, List.of());
        assertInvalid(range(130, 130), ValidationCode.TARGET_RANGE, List.of());
        assertInvalid(SettingsRules.validate(defaults, new SettingChange(SettingKey.TARGET_RANGE, 70, null)),
                ValidationCode.TARGET_RANGE, List.of());
    }

    private ValidationResult range(int from, int to) {
        return SettingsRules.validate(defaults, new SettingChange(SettingKey.TARGET_RANGE, from, to));
    }
}
