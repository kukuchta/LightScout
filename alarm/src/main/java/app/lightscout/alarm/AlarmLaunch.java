package app.lightscout.alarm;

import android.content.Context;
import android.content.Intent;

import app.lightscout.domain.alarm.AlarmType;

/**
 * Hand-off entry for CP-15 (CP-00A R-36, R-86; F11, FL-02 „Background → return”): {@code MainActivity.onResume}
 * starts S-09 with {@link #screenIntent} when {@code StatusRepository.current().ringing != null}. The routing itself
 * is owned by CP-15; S-09 offers no way back into the app (SR-25).
 */
public final class AlarmLaunch {

    private AlarmLaunch() {
    }

    /**
     * Returns the explicit intent that opens S-09 for a ringing type ({@code alarmType} extra, own task).
     *
     * @param context any context of this app
     * @param ringing the ringing type ({@code StatusSnapshot.ringing})
     * @return the intent for {@code startActivity}
     */
    public static Intent screenIntent(Context context, AlarmType ringing) {
        return AlarmIntents.screen(context, ringing);
    }
}
