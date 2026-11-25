package app.lightscout.monitor;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;

/**
 * {@link AlarmPort} over {@code AlarmManager}: {@code ELAPSED_REALTIME_WAKEUP} for every exact / inexact alarm
 * (F14); {@code setAlarmClock} (wall clock by API) only for the VR-07 fallback. No RTC alarm types.
 */
final class AndroidAlarmPort implements AlarmPort {

    private final Context context;
    private final AlarmManager alarmManager;

    /**
     * Creates the adapter.
     *
     * @param context any context (the application context is kept)
     */
    AndroidAlarmPort(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = this.context.getSystemService(AlarmManager.class);
    }

    @Override
    public boolean canScheduleExact() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarmManager.canScheduleExactAlarms();
        }
        return true;
    }

    @Override
    public void setExactElapsed(Kind kind, long elapsedMs) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedMs,
                MonitorIntents.alarm(context, kind));
    }

    @Override
    public void setInexactElapsed(Kind kind, long elapsedMs) {
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedMs,
                MonitorIntents.alarm(context, kind));
    }

    @Override
    public void setAlarmClock(Kind kind, long wallMs) {
        alarmManager.setAlarmClock(new AlarmManager.AlarmClockInfo(wallMs, MonitorIntents.alarmClockShow(context)),
                MonitorIntents.alarm(context, kind));
    }

    @Override
    public void cancel(Kind kind) {
        alarmManager.cancel(MonitorIntents.alarm(context, kind));
    }
}
