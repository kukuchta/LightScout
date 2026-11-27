package app.lightscout.feature.settings;

import android.content.Context;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

import java.util.ArrayList;
import java.util.List;

/** The words of the sound screens (DCR-51): alarm names, sound names and the S-04 summary. All from resources. */
final class SoundText {

    /** Most alarm names the S-04 summary lists before it says "+N". */
    private static final int SUMMARY_NAMES = 2;

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

    /** The support text of the S-04 row: "LightScout classic", or "Custom: Low, High" with at most two names. */
    static String summary(Context context, List<AlarmType> customised) {
        if (customised.isEmpty()) {
            return context.getString(R.string.sound_classic);
        }
        List<String> names = new ArrayList<>();
        for (AlarmType type : customised.subList(0, Math.min(SUMMARY_NAMES, customised.size()))) {
            names.add(context.getString(alarmName(type)));
        }
        String joined = String.join(", ", names);
        int more = customised.size() - names.size();
        return more > 0 ? context.getString(R.string.s04_alarm_sounds_custom_more, joined, more)
                : context.getString(R.string.s04_alarm_sounds_custom, joined);
    }
}
