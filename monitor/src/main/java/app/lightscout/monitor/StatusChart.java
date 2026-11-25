package app.lightscout.monitor;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.ContextThemeWrapper;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.ui.core.GlucoseRendering;
import app.lightscout.usecases.HistoryLoader;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The simplified last-two-hours chart of S-10 (DCR-37): readings as dots and the Low / High guides in the main chart's colours, no labels. Bitmaps are
 * rendered on a background executor from the readings cache and published atomically; {@code build} only reads the
 * last published ones, so the notification is never blocked by storage. The bitmaps match the fixed dp size of
 * {@code ns_chart} in the two layouts.
 */
final class StatusChart {

    static final Duration WINDOW = Duration.ofHours(2);
    static final int COLLAPSED_WIDTH_DP = 160;
    static final int COLLAPSED_HEIGHT_DP = 40;
    static final int EXPANDED_WIDTH_DP = 170;
    static final int EXPANDED_HEIGHT_DP = 104;

    /** Both sizes of one render, published together. */
    record Bitmaps(Bitmap collapsed, Bitmap expanded) {
    }

    private final Context themed;
    private final Supplier<HistoryLoader> history;
    private final Clock clock;
    private final Executor executor;
    private volatile Bitmaps current;

    StatusChart(Context context, Supplier<HistoryLoader> history, Clock clock, Executor executor) {
        this.themed = new ContextThemeWrapper(context.getApplicationContext(),
                app.lightscout.ui.core.R.style.Theme_LightScout);
        this.history = history;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * Returns the last rendered chart.
     *
     * @return the bitmaps, or {@code null} before the first render or when the history is unavailable
     */
    Bitmaps current() {
        return current;
    }

    /**
     * Renders the chart for the snapshot's thresholds on the executor and calls {@code onUpdated} (on that executor)
     * when new bitmaps were published. A failure keeps the previous chart.
     *
     * @param snapshot  snapshot with the thresholds; {@code null} is ignored
     * @param onUpdated called after publishing
     */
    void refresh(StatusSnapshot snapshot, Runnable onUpdated) {
        if (snapshot == null) {
            return;
        }
        int low = snapshot.lowMgdl();
        int high = snapshot.highMgdl();
        executor.execute(() -> {
            try {
                HistoryLoader loader = history.get();
                if (loader == null) {
                    return;
                }
                Instant to = clock.now();
                Instant from = to.minus(WINDOW);
                List<Reading> readings = List.copyOf(loader.cachedReadings(from, to));
                current = new Bitmaps(
                        render(readings, from, to, low, high, COLLAPSED_WIDTH_DP, COLLAPSED_HEIGHT_DP),
                        render(readings, from, to, low, high, EXPANDED_WIDTH_DP, EXPANDED_HEIGHT_DP));
                onUpdated.run();
            } catch (RuntimeException ignored) {
                // The notification stays without a refreshed chart; monitoring is unaffected.
            }
        });
    }

    private Bitmap render(List<Reading> readings, Instant from, Instant to, int low, int high, int widthDp,
                          int heightDp) {
        float density = themed.getResources().getDisplayMetrics().density;
        return GlucoseRendering.renderNotificationChart(themed, readings, from, to, low, high,
                Math.round(widthDp * density), Math.round(heightDp * density));
    }
}
