package app.lightscout.monitor;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Explicit, immutable intents and PendingIntents of {@code :monitor} (SR-22, CP-00A R-36): unique request code per
 * purpose; AlarmManager → {@link MonitorAlarmReceiver} / {@link WatchdogReceiver}; service starts → explicit
 * {@link MonitorService} component.
 */
final class MonitorIntents {

    /** {@code MonitorAlarmReceiver}: sync tick. */
    static final String ACTION_TICK = "app.lightscout.monitor.action.TICK";
    /** {@code MonitorAlarmReceiver}: ringing heartbeat. */
    static final String ACTION_HEARTBEAT = "app.lightscout.monitor.action.HEARTBEAT";
    /** {@code MonitorAlarmReceiver}: WF-04 boot retry. */
    static final String ACTION_BOOT_RETRY = "app.lightscout.monitor.action.BOOT_RETRY";
    /** {@code WatchdogReceiver}: watchdog. */
    static final String ACTION_WATCHDOG = "app.lightscout.monitor.action.WATCHDOG";
    /** {@code MonitorService} extra: {@link StartOrigin} name (internal, non-exported service). */
    static final String EXTRA_ORIGIN = "app.lightscout.monitor.extra.ORIGIN";

    private static final int RC_TICK = 801;
    private static final int RC_HEARTBEAT = 802;
    private static final int RC_BOOT_RETRY = 803;
    private static final int RC_WATCHDOG = 804;
    private static final int RC_ALARM_CLOCK_SHOW = 805;

    private MonitorIntents() {
    }

    /**
     * Builds the alarm PendingIntent of a kind.
     *
     * @param context context
     * @param kind    alarm
     * @return immutable explicit broadcast PendingIntent
     */
    static PendingIntent alarm(Context context, AlarmPort.Kind kind) {
        Intent intent;
        int requestCode;
        switch (kind) {
            case TICK -> {
                intent = new Intent(context, MonitorAlarmReceiver.class).setAction(ACTION_TICK);
                requestCode = RC_TICK;
            }
            case HEARTBEAT -> {
                intent = new Intent(context, MonitorAlarmReceiver.class).setAction(ACTION_HEARTBEAT);
                requestCode = RC_HEARTBEAT;
            }
            case BOOT_RETRY -> {
                intent = new Intent(context, MonitorAlarmReceiver.class).setAction(ACTION_BOOT_RETRY);
                requestCode = RC_BOOT_RETRY;
            }
            default -> {
                intent = new Intent(context, WatchdogReceiver.class).setAction(ACTION_WATCHDOG);
                requestCode = RC_WATCHDOG;
            }
        }
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Show intent of the {@code setAlarmClock} fallback (opens the app; immutable, explicit).
     *
     * @param context context
     * @return the PendingIntent, or {@code null} when the package has no launcher activity (library tests)
     */
    static PendingIntent alarmClockShow(Context context) {
        try {
            Intent launch = app.lightscout.ui.core.AppLaunch.mainActivityIntent(context);
            return PendingIntent.getActivity(context, RC_ALARM_CLOCK_SHOW, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * Builds the explicit service intent for an origin.
     *
     * @param context context
     * @param origin  origin
     * @return intent
     */
    static Intent service(Context context, StartOrigin origin) {
        return new Intent(context, MonitorService.class).putExtra(EXTRA_ORIGIN, origin.name());
    }

    /**
     * Reads the origin of a service start; a {@code null} intent is the {@code START_STICKY} restart.
     *
     * @param intent the start intent (nullable)
     * @return origin
     */
    static StartOrigin origin(Intent intent) {
        if (intent == null) {
            return StartOrigin.STICKY_RESTART;
        }
        return parseOrigin(intent.getStringExtra(EXTRA_ORIGIN));
    }

    /**
     * Parses an origin name; unknown or missing → {@code STICKY_RESTART} (a tick — never nothing, SR-37).
     *
     * @param name name (nullable)
     * @return origin
     */
    static StartOrigin parseOrigin(String name) {
        if (name != null) {
            for (StartOrigin o : StartOrigin.values()) {
                if (o.name().equals(name)) {
                    return o;
                }
            }
        }
        return StartOrigin.STICKY_RESTART;
    }
}
