package app.lightscout.feature.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import app.lightscout.domain.statistics.Statistics;

/** Existing 24-hour calculation rendered in original five-band order; the card owns navigation/accessibility. */
public final class HomeDistributionView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Statistics statistics;
    private final int[] colors;
    private final double[] bands = new double[5];
    private boolean enoughData;

    public HomeDistributionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        colors = new int[] {
                context.getColor(app.lightscout.ui.core.R.color.glass_band_very_low),
                context.getColor(app.lightscout.ui.core.R.color.glass_band_low),
                context.getColor(app.lightscout.ui.core.R.color.glass_band_in_range),
                context.getColor(app.lightscout.ui.core.R.color.glass_band_high),
                context.getColor(app.lightscout.ui.core.R.color.glass_band_very_high)
        };
    }

    void setStatistics(Statistics value) {
        statistics = value;
        enoughData = value != null && !value.empty() && !value.partial();
        if (value != null) {
            bands[0] = value.veryLowPct();
            bands[1] = value.lowPct();
            bands[2] = value.inRangePct();
            bands[3] = value.highPct();
            bands[4] = value.veryHighPct();
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enoughData) {
            return;
        }
        float start = 0;
        float density = getResources().getDisplayMetrics().density;
        // Glass (mockup): 2 dp gaps and 3 dp rounded segments, drawn straight on the card.
        float gap = 2 * density;
        float radius = 3 * density;
        for (int i = 0; i < bands.length; i++) {
            float end = start + (float) (bands[i] * getWidth() / 100d);
            paint.setColor(colors[i]);
            if (end > start) {
                float right = Math.max(start, end - gap);
                canvas.drawRoundRect(start, 0, right, getHeight(), radius, radius, paint);
            }
            start = end;
        }
    }

    static Values values(Statistics statistics) {
        return new Values(!statistics.empty() && !statistics.partial(),
                (int) Math.round(statistics.veryLowPct()), (int) Math.round(statistics.lowPct()),
                (int) Math.round(statistics.inRangePct()), (int) Math.round(statistics.highPct()),
                (int) Math.round(statistics.veryHighPct()));
    }

    record Values(boolean enoughData, int veryLow, int low, int inRange, int high, int veryHigh) { }
}
