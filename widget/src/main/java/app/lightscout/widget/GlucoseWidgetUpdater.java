package app.lightscout.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.SizeF;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.ui.core.AppLaunch;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.GlucoseRendering;
import app.lightscout.ui.core.TrendIcons;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.StatusRepository;

/**
 * App-pushed S-12 renderer. Snapshot callbacks run on the monitor executor; history and bitmap work therefore never
 * run on the main thread.
 */
public final class GlucoseWidgetUpdater implements StatusListener, ComponentCallbacks {

    private static final int TAP_REQUEST_CODE = 1201;
    private static final float DELTA_SP = 20f;
    private static final float AGE_SP = 14f;
    private static final long THREE_HOURS_SECONDS = 3L * 60L * 60L;
    private static final int DEFAULT_WIDTH_DP = 250;
    private static final int DEFAULT_HEIGHT_DP = 160;

    private final Context applicationContext;
    private final StatusRepository statusRepository;
    private final HistoryLoader historyLoader;
    private final Clock clock;
    private final Executor renderExecutor;
    private final AppWidgetManager manager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WidgetRenderThrottle throttle = new WidgetRenderThrottle();
    private final WidgetRenderQueue<StatusSnapshot> pendingRenderQueue = new WidgetRenderQueue<>();
    private final Object pendingRenderLock = new Object();
    private boolean pendingRenderScheduled;
    private volatile int nightMode;
    private volatile float fontScale;
    private volatile int densityDpi;

    /**
     * Creates and registers the process-wide widget updater.
     */
    public GlucoseWidgetUpdater(Context context, StatusRepository statusRepository, HistoryLoader historyLoader,
                                Clock clock, Executor renderExecutor) {
        applicationContext = Objects.requireNonNull(context, "context").getApplicationContext();
        this.statusRepository = Objects.requireNonNull(statusRepository, "statusRepository");
        this.historyLoader = Objects.requireNonNull(historyLoader, "historyLoader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.renderExecutor = Objects.requireNonNull(renderExecutor, "renderExecutor");
        manager = AppWidgetManager.getInstance(applicationContext);
        nightMode = applicationContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        fontScale = applicationContext.getResources().getConfiguration().fontScale;
        densityDpi = applicationContext.getResources().getConfiguration().densityDpi;
        statusRepository.addListener(this);
    }

    @Override
    public void onStatus(StatusSnapshot snapshot) {
        renderIfAllowed(snapshot, null);
    }

    /**
     * Requests an update for the provided launcher widget ids. No other incoming values are read.
     */
    public void updateWidgets(int[] appWidgetIds) {
        int[] ids = appWidgetIds == null ? null : appWidgetIds.clone();
        renderExecutor.execute(() -> renderIfAllowed(statusRepository.current(), ids));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        int newNightMode = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (newNightMode == nightMode && newConfig.fontScale == fontScale && newConfig.densityDpi == densityDpi) {
            return;
        }
        nightMode = newNightMode;
        fontScale = newConfig.fontScale;
        densityDpi = newConfig.densityDpi;
        renderExecutor.execute(() -> renderForThemeChange(statusRepository.current()));
    }

    @Override
    public void onLowMemory() {
        // No widget state is retained beyond the current render.
    }

    private void renderForThemeChange(StatusSnapshot snapshot) {
        renderIfAllowed(snapshot, null);
    }

    private void renderIfAllowed(StatusSnapshot snapshot, int[] requestedIds) {
        int[] ids = requestedIds == null ? manager.getAppWidgetIds(
                new android.content.ComponentName(applicationContext, GlucoseWidgetProvider.class)) : requestedIds;
        if (ids.length == 0) {
            return;
        }
        long now = clock.elapsedRealtimeMs();
        if (!throttle.tryAcquire(now)) {
            schedulePendingRender(snapshot, requestedIds, throttle.millisUntilNext(now));
            return;
        }
        synchronized (pendingRenderLock) {
            pendingRenderQueue.replaceSnapshotIfPending(snapshot);
        }
        render(snapshot, ids);
    }

    private void schedulePendingRender(StatusSnapshot snapshot, int[] requestedIds, long delayMs) {
        synchronized (pendingRenderLock) {
            pendingRenderQueue.offer(snapshot, requestedIds);
            if (pendingRenderScheduled) {
                return;
            }
            pendingRenderScheduled = true;
        }
        mainHandler.postDelayed(() -> renderExecutor.execute(this::renderPending), Math.max(1L, delayMs));
    }

    private void renderPending() {
        WidgetRenderQueue.Request<StatusSnapshot> request;
        synchronized (pendingRenderLock) {
            request = pendingRenderQueue.poll();
            pendingRenderScheduled = false;
        }
        if (request != null) {
            renderIfAllowed(request.snapshot(), request.widgetIds());
        }
    }

    private void render(StatusSnapshot snapshot, int[] requestedIds) {
        int[] ids = requestedIds == null ? manager.getAppWidgetIds(
                new android.content.ComponentName(applicationContext, GlucoseWidgetProvider.class)) : requestedIds;
        if (ids.length == 0) {
            return;
        }

        Instant now = clock.now();
        Instant from = now.minusSeconds(THREE_HOURS_SECONDS);
        List<Reading> readings = List.copyOf(historyLoader.cachedReadings(from, now));
        WidgetPresentation presentation = WidgetStateMapper.map(snapshot, now);
        PendingIntent tapIntent = PendingIntent.getActivity(applicationContext, TAP_REQUEST_CODE,
                AppLaunch.mainActivityIntent(applicationContext),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (int widgetId : ids) {
            RemoteViews views = buildViews(presentation, readings, from, now, widgetId, tapIntent);
            manager.updateAppWidget(widgetId, views);
        }
    }

    private RemoteViews buildViews(WidgetPresentation presentation, List<Reading> readings, Instant from, Instant to,
                                   int widgetId, PendingIntent tapIntent) {
        Context themed = darkThemed();
        Bundle options = manager.getAppWidgetOptions(widgetId);
        if (options == null) {
            options = Bundle.EMPTY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty() && sizes.size() <= 16) {
                Map<SizeF, RemoteViews> variants = new LinkedHashMap<>();
                for (SizeF size : sizes) {
                    if (size != null && size.getWidth() > 0 && size.getHeight() > 0) {
                        variants.put(size, buildViews(themed, presentation, readings, from, to,
                                size.getWidth(), size.getHeight(), tapIntent));
                    }
                }
                if (!variants.isEmpty()) {
                    return new RemoteViews(variants);
                }
            }
        }
        int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH_DP);
        int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_HEIGHT_DP);
        int maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth);
        int maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight);
        // The minima describe different orientations, not a single small rectangle.
        return new RemoteViews(
                buildViews(themed, presentation, readings, from, to, maxWidth, minHeight, tapIntent),
                buildViews(themed, presentation, readings, from, to, minWidth, maxHeight, tapIntent));
    }

    /** S-12 is always dark (DCR-38): resources and chart colours come from the night configuration. */
    private Context darkThemed() {
        Configuration night = new Configuration(applicationContext.getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        return new ContextThemeWrapper(applicationContext.createConfigurationContext(night),
                app.lightscout.ui.core.R.style.Theme_LightScout);
    }

    static RemoteViews buildViews(Context themed, WidgetPresentation presentation, List<Reading> readings,
                                  Instant from, Instant to, float widthDp, float heightDp, PendingIntent tapIntent) {
        float density = themed.getResources().getDisplayMetrics().density;
        WidgetLayout.Measured measured = WidgetLayout.measure(themed, () -> {
            RemoteViews candidate = new RemoteViews(themed.getPackageName(), R.layout.widget_glucose);
            candidate.setOnClickPendingIntent(R.id.widget_root, tapIntent);
            bindPresentation(candidate, themed, presentation);
            return candidate;
        }, Math.max(1, Math.round(widthDp * density)), Math.max(1, Math.round(heightDp * density)));
        RemoteViews views = measured.views();
        View chartBox = measured.root().findViewById(R.id.widget_chart);
        int widthPx = Math.max(1, chartBox.getWidth());
        int heightPx = Math.max(1, chartBox.getHeight());
        Bitmap chart = GlucoseRendering.renderNotificationChart(themed, readings, from, to,
                presentation.lowMgdl(), presentation.highMgdl(), widthPx, heightPx);
        views.setImageViewBitmap(R.id.widget_chart, chart);
        return views;
    }

    private static void bindPresentation(RemoteViews views, Context themed, WidgetPresentation presentation) {
        String description;
        switch (presentation.state()) {
            case FIRST_USE -> {
                bindEmpty(views, themed, R.string.s12_first_use);
                description = themed.getString(R.string.s12_first_use);
            }
            case NO_READINGS -> {
                bindEmpty(views, themed, R.string.s12_no_readings);
                description = themed.getString(R.string.s12_no_readings);
            }
            case CONTENT -> {
                Reading latest = presentation.latest();
                views.setTextViewText(R.id.widget_reading, Integer.toString(latest.sgv()));
                views.setViewVisibility(R.id.widget_empty, View.GONE);
                int lineColor = lineColor(themed, presentation.rangeLabel());
                views.setTextColor(R.id.widget_reading, lineColor);
                bindDelta(views, presentation.deltaMgdl());
                bindTrend(views, themed, latest);
                views.setTextViewText(R.id.widget_age, ageText(Formatters.readingAge(themed, presentation.age())));
                int ageColor = themed.getColor(presentation.stale()
                        ? R.color.widget_stale : R.color.widget_on_surface_variant);
                views.setTextColor(R.id.widget_age, ageColor);
                views.setViewVisibility(R.id.widget_stale_icon, presentation.stale() ? View.VISIBLE : View.GONE);
                if (presentation.stale()) {
                    views.setInt(R.id.widget_stale_icon, "setColorFilter", ageColor);
                }
                CharSequence spokenAge = Formatters.readingAgeSpoken(themed, presentation.age());
                description = themed.getString(R.string.s12_widget_a11y, latest.sgv(),
                        Formatters.trendWords(themed, latest.trend()), spokenAge);
            }
            default -> throw new IllegalStateException("Unknown widget state");
        }
        views.setContentDescription(R.id.widget_root, description);
    }

    private static void bindEmpty(RemoteViews views, Context themed, int messageRes) {
        views.setTextViewText(R.id.widget_reading, themed.getString(R.string.s01_reading_empty));
        views.setTextViewText(R.id.widget_age, "");
        views.setTextViewText(R.id.widget_empty, themed.getString(messageRes));
        views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
        views.setTextColor(R.id.widget_reading, themed.getColor(R.color.widget_on_surface));
        views.setViewVisibility(R.id.widget_delta, View.GONE);
        views.setViewVisibility(R.id.widget_delta_unit, View.GONE);
        views.setViewVisibility(R.id.widget_trend, View.GONE);
        views.setViewVisibility(R.id.widget_stale_icon, View.GONE);
    }

    /**
     * The age line: its number in the size of the delta number, the rest ("min ago") in the size of the unit.
     */
    private static CharSequence ageText(String age) {
        int digits = 0;
        while (digits < age.length() && Character.isDigit(age.charAt(digits))) {
            digits++;
        }
        if (digits == 0) {
            return age;
        }
        SpannableString text = new SpannableString(age);
        text.setSpan(new RelativeSizeSpan(DELTA_SP / AGE_SP), 0, digits, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** Delta with its unit ("+3 mg/dL", "-5 mg/dL", "+0 mg/dL"): the same signs as the S-10 title. */
    private static void bindDelta(RemoteViews views, Integer delta) {
        if (delta == null) {
            views.setViewVisibility(R.id.widget_delta, View.GONE);
            views.setViewVisibility(R.id.widget_delta_unit, View.GONE);
            return;
        }
        views.setTextViewText(R.id.widget_delta, delta >= 0 ? "+" + delta : Integer.toString(delta));
        views.setViewVisibility(R.id.widget_delta, View.VISIBLE);
        views.setViewVisibility(R.id.widget_delta_unit, View.VISIBLE);
    }

    private static void bindTrend(RemoteViews views, Context themed, Reading latest) {
        int arrow = TrendIcons.drawableFor(latest.trend());
        if (arrow == 0) {
            views.setViewVisibility(R.id.widget_trend, View.GONE);
            return;
        }
        views.setImageViewResource(R.id.widget_trend, arrow);
        views.setInt(R.id.widget_trend, "setColorFilter", themed.getColor(R.color.widget_on_surface));
        views.setContentDescription(R.id.widget_trend, Formatters.trendWords(themed, latest.trend()));
        views.setViewVisibility(R.id.widget_trend, View.VISIBLE);
    }

    /** Only the reading takes the colour of the High / Low guide when it is out of range (as in the chart). */
    private static int lineColor(Context themed, WidgetPresentation.RangeLabel label) {
        return switch (label) {
            case URGENT_LOW, LOW -> themed.getColor(app.lightscout.ui.core.R.color.glass_guide_low);
            case HIGH -> themed.getColor(app.lightscout.ui.core.R.color.notification_guide_high);
            case NONE -> themed.getColor(R.color.widget_on_surface);
        };
    }
}
