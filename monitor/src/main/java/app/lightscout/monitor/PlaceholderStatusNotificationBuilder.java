package app.lightscout.monitor;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.ui.core.AppLaunch;
import app.lightscout.ui.core.Formatters;


/**
 * M1 placeholder for the S-10 notification (CP-00A R-81): resource small icon {@code ic_stat_lightscout} (F4),
 * title {@code s10_title} („{sgv} mg/dL {arrow}”) when a reading exists, otherwise {@code s10_title_empty}
 * („No readings”, Q-57; „—” is only for the stale number icon), no text,
 * ongoing, silent, alert-once, CH-02 (DEFAULT importance since DCR-06, never audible), PUBLIC; tap opens the app
 * ({@code AppLaunch}, immutable). Replaced by CP-09.
 */
final class PlaceholderStatusNotificationBuilder implements StatusNotificationBuilder {

    private static final int RC_CONTENT = 810;

    private final Context context;
    private final String channelId;

    /**
     * Creates the builder.
     *
     * @param context   context
     * @param channelId CH-02 id ({@code NotificationChannels.channelId(MONITORING)})
     */
    PlaceholderStatusNotificationBuilder(Context context, String channelId) {
        this.context = context.getApplicationContext();
        this.channelId = channelId;
    }

    @Override
    public Notification build(StatusSnapshot s) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(app.lightscout.ui.core.R.drawable.ic_stat_lightscout)
                .setContentTitle(title(s))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)   // matches CH-02 importance (DCR-06)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        PendingIntent content = contentIntent();
        if (content != null) {
            b.setContentIntent(content);
        }
        return b.build();
    }

    private String title(StatusSnapshot s) {
        Reading latest = latestOrNull(s);
        if (latest == null) {
            return context.getString(R.string.s10_title_empty);
        }
        String arrow = Formatters.trendArrow(context, latest.trend());
        return context.getString(R.string.s10_title, latest.sgv(), arrow).trim();
    }

    /**
     * R-81 / Q-57 title rule: {@code latest == null} → {@code s10_title_empty}, otherwise {@code s10_title}.
     *
     * @param s snapshot (nullable before the first projection)
     * @return the latest reading, or {@code null} when the empty title applies
     */
    static Reading latestOrNull(StatusSnapshot s) {
        return s != null ? s.latest() : null;
    }

    private PendingIntent contentIntent() {
        try {
            return PendingIntent.getActivity(context, RC_CONTENT, AppLaunch.mainActivityIntent(context),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (IllegalStateException e) {
            return null;   // no launcher activity (library instrumented tests)
        }
    }
}
