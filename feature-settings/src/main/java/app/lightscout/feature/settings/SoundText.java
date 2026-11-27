package app.lightscout.feature.settings;

import android.content.Context;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

/** The words of the sound screens (DCR-51): alarm names and sound names. All from resources. */
final class SoundText {

    private SoundText() {
    }

    static int alarmName(AlarmType type) {
        return switch (type) {
            case URGENT_LOW -> R.string.alarm_name_urgent_low;
            case LOW -> R.string.alarm_name_low;
            case NO_DATA -> R.string.alarm_name_no_data;
            case NOT_RUNNING -> R.string.alarm_name_not_running;
            case HIGH -> R.string.alarm_name_high;
        };
    }

    /** The name a row shows for a choice: the stored name, or "LightScout classic". */
    static String soundName(Context context, SoundLibrary library, SoundRef ref) {
        String custom = SoundLabels.customName(library, ref);
        return custom != null ? custom : context.getString(R.string.sound_classic);
    }

}
