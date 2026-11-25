package app.lightscout.monitor;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TtsSpan;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;

import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.ui.core.AppLaunch;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.GlucoseRendering;
import app.lightscout.ui.core.TrendIcons;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.NotificationChannels;

/** Full S-10 notification builder (CP-09; CP-00A R-81). */
final class PersistentStatusNotificationBuilder implements StatusNotificationBuilder {

    private static final int RC_CONTENT = 810;

    private final Context context;
    private final String channelId;
    private final Clock clock;
    private boolean foregroundStarted;
    private volatile StatusChart chart;

    PersistentStatusNotificationBuilder(Context context, NotificationChannels channels, Clock clock) {
        this.context = context.getApplicationContext();
        this.channelId = channels.channelId(ChannelId.MONITORING);
        this.clock = clock;
    }

    @Override
    public Notification build(StatusSnapshot snapshot) {
        StatusNotificationContent.Content content = StatusNotificationContent.create(snapshot, clock.now(),
                clock.zone(), new ResourceStrings(), new ResourceFormatter());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentTitle(title(content))
                .setContentText(content.text())
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        if (!foregroundStarted) {
            builder.setSmallIcon(app.lightscout.ui.core.R.drawable.ic_stat_lightscout);
        } else {
            IconCompat icon = GlucoseRendering.statusNumberIcon(context, content.iconSgv());
            builder.setSmallIcon(icon);
        }
        applyChartLayouts(builder, content);
        PendingIntent contentIntent = contentIntent();
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        return builder.build();
    }

    /** DCR-37: text left, last two hours right. Without a chart yet the standard layout (title and text) stays. */
    private void applyChartLayouts(NotificationCompat.Builder builder, StatusNotificationContent.Content content) {
        StatusChart source = chart;
        StatusChart.Bitmaps bitmaps = source == null ? null : source.current();
        if (bitmaps == null) {
            return;
        }
        builder.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(chartViews(R.layout.notification_status_collapsed, content, bitmaps.collapsed()))
                .setCustomBigContentView(chartViews(R.layout.notification_status_expanded, content, bitmaps.expanded()));
    }

    private RemoteViews chartViews(int layout, StatusNotificationContent.Content content, Bitmap chartBitmap) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setTextViewText(R.id.ns_title, content.headline());
        views.setTextViewText(R.id.ns_text, content.text());
        views.setImageViewBitmap(R.id.ns_chart, chartBitmap);
        int arrow = TrendIcons.drawableFor(content.trend());
        if (arrow == 0) {
            views.setViewVisibility(R.id.ns_trend, android.view.View.GONE);
        } else {
            views.setViewVisibility(R.id.ns_trend, android.view.View.VISIBLE);
            views.setImageViewResource(R.id.ns_trend, arrow);
            views.setInt(R.id.ns_trend, "setColorFilter",
                    context.getColor(app.lightscout.ui.core.R.color.md_theme_onSurface));
            views.setContentDescription(R.id.ns_trend, content.spokenTrend());
        }
        String spoken = content.spokenTrend().isEmpty() ? "" : " " + content.spokenTrend();
        views.setContentDescription(R.id.ns_root, content.headline() + spoken + ", " + content.text());
        return views;
    }

    @Override
    public void setChart(StatusChart chart) {
        this.chart = chart;
    }

    @Override
    public void onForegroundStarted() {
        foregroundStarted = true;
    }

    private CharSequence title(StatusNotificationContent.Content content) {
        String text = content.title();
        CharSequence base = text;
        if (!content.spokenTrend().isEmpty()) {
            int arrowStart = text.lastIndexOf(' ');
            if (arrowStart >= 0 && arrowStart != text.length() - 1) {
                SpannableString spoken = new SpannableString(text);
                spoken.setSpan(new TtsSpan.TextBuilder(content.spokenTrend()).build(), arrowStart + 1,
                        spoken.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                base = spoken;
            }
        }
        return base;
    }

    private PendingIntent contentIntent() {
        try {
            return PendingIntent.getActivity(context, RC_CONTENT, AppLaunch.mainActivityIntent(context),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private final class ResourceStrings implements StatusNotificationContent.Strings {
        @Override
        public String titleEmpty() {
            return context.getString(R.string.s10_title_empty);
        }

        @Override
        public String reading(int sgv, String signedDelta) {
            return signedDelta == null ? context.getString(R.string.s10_reading, sgv)
                    : context.getString(R.string.s10_reading_delta, sgv, signedDelta);
        }

        @Override
        public String firstUse() {
            return context.getString(R.string.s10_text_first_use);
        }

        @Override
        public String dataLoss(long minutes) {
            return context.getString(R.string.s10_text_data_loss, minutes);
        }

        @Override
        public String offline(long minutes) {
            return context.getString(R.string.s10_offline, minutes);
        }

        @Override
        public String errorNotResponding() {
            return context.getString(R.string.s10_error_ns_not_responding);
        }

        @Override
        public String errorTokenRejected() {
            return context.getString(R.string.s10_error_token_rejected);
        }

        @Override
        public String normal(String age) {
            return context.getString(R.string.s10_text, age);
        }

        @Override
        public String alarmsOffSuffix() {
            return context.getString(R.string.s10_text_alarms_off_suffix);
        }
    }

    private final class ResourceFormatter implements StatusNotificationContent.Formatter {
        @Override
        public String age(java.time.Duration age) {
            return Formatters.readingAge(context, age);
        }

        @Override
        public String arrow(Trend trend) {
            return Formatters.trendArrow(context, trend);
        }

        @Override
        public String spokenTrend(Trend trend) {
            return Formatters.trendWords(context, trend).toString();
        }
    }
}
