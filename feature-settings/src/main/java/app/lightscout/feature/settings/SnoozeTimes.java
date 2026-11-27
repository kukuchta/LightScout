package app.lightscout.feature.settings;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;

import java.util.List;

/**
 * The rows of S-15 Snooze times: one per alarm type that has its own snooze, in the order of the sound screen. Not
 * running has no row because it uses the No data snooze (06 section 11 F20). No Android classes.
 */
final class SnoozeTimes {

    /** One row: the alarm it belongs to and the setting its dialog edits. */
    record Row(AlarmType type, SettingKey key) {
    }

    static final List<Row> ROWS = List.of(
            new Row(AlarmType.URGENT_LOW, SettingKey.URGENT_LOW_SNOOZE),
            new Row(AlarmType.LOW, SettingKey.LOW_SNOOZE),
            new Row(AlarmType.NO_DATA, SettingKey.NO_DATA_SNOOZE),
            new Row(AlarmType.HIGH, SettingKey.HIGH_SNOOZE));

    private SnoozeTimes() {
    }

    /** The stored snooze of a row in minutes. */
    static int minutes(Settings settings, Row row) {
        return settings.snoozeMinutes(row.type());
    }
}
