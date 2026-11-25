package app.lightscout.feature.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import app.lightscout.domain.statistics.Statistics;

/** Five-band range distribution bar; labels and values are supplied by the adjacent accessible legend. */
public final class DistributionBarView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF segment = new RectF();
    private Statistics statistics;
    private int[] fills;
    private float density;

    public DistributionBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription(null);
        fills = new int[] {
                getContext().getColor(app.lightscout.ui.core.R.color.glass_band_very_high),
                getContext().getColor(app.lightscout.ui.core.R.color.glass_band_high),
                getContext().getColor(app.lightscout.ui.core.R.color.glass_band_in_range),
                getContext().getColor(app.lightscout.ui.core.R.color.glass_band_low),
                getContext().getColor(app.lightscout.ui.core.R.color.glass_band_very_low)
        };
    }

    public void setStatistics(@Nullable Statistics value) {
        statistics = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Statistics data = statistics;
        if (data == null || data.empty() || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        // Bar runs very low to very high (left to right); the legend keeps its own very high to very low order.
        double[] values = {data.veryLowPct(), data.lowPct(), data.inRangePct(), data.highPct(), data.veryHighPct()};
        float gap = 2 * density;
        float radius = 3 * density;
        // Gaps are reserved only between bands that are drawn: a lone band spans the full width.
        int drawn = 0;
        int lastDrawn = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > 0) {
                drawn++;
                lastDrawn = i;
            }
        }
        float available = Math.max(0, getWidth() - gap * Math.max(0, drawn - 1));
        float left = 0;
        float top = 0;
        float bottom = getHeight();
        for (int i = 0; i < values.length; i++) {
            if (values[i] <= 0) {
                continue;
            }
            float right = i == lastDrawn ? getWidth() : left + (float) (available * values[i] / 100d);
            if (right > left) {
                segment.set(left, top, right, bottom);
                paint.setColor(fills[fills.length - 1 - i]);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(segment, radius, radius, paint);
            }
            left = right + gap;
        }
    }
}
