package app.lightscout.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;

import java.util.List;
import org.junit.Test;

/** S-15 Snooze times: which rows the screen has, in which order, and which setting each one edits. */
public class SnoozeTimesTest {

    private final Settings settings = new Settings(true, true, 70, 55, true, 250, 20,
            11, 12, 13, 14, 3, 10, new TargetRange(70, 180));

    @Test
    public void rowsFollowTheOrderOfTheSoundScreen() {
        List<AlarmType> types = SnoozeTimes.ROWS.stream().map(SnoozeTimes.Row::type).toList();
        assertEquals(List.of(AlarmType.URGENT_LOW, AlarmType.LOW, AlarmType.NO_DATA, AlarmType.HIGH), types);
    }

    @Test
    public void notRunningHasNoRowBecauseItUsesTheNoDataSnooze() {
        assertFalse(SnoozeTimes.ROWS.stream().anyMatch(row -> row.type() == AlarmType.NOT_RUNNING));
    }

    @Test
    public void everyRowEditsTheSnoozeOfItsOwnAlarmType() {
        List<SettingKey> keys = SnoozeTimes.ROWS.stream().map(SnoozeTimes.Row::key).toList();
        assertEquals(List.of(SettingKey.URGENT_LOW_SNOOZE, SettingKey.LOW_SNOOZE, SettingKey.NO_DATA_SNOOZE,
                SettingKey.HIGH_SNOOZE), keys);
    }

    @Test
    public void everyRowShowsTheStoredMinutesOfItsAlarmType() {
        assertEquals(List.of(12, 11, 14, 13),
                SnoozeTimes.ROWS.stream().map(row -> SnoozeTimes.minutes(settings, row)).toList());
    }
}
