package app.lightscout.ui.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.time.Instant;
import java.util.List;

import app.lightscout.domain.model.Reading;

/**
 * Draws the S-10 notification chart (DCR-37): readings as dots (the main chart's 2 dp dot, coloured per Low / High /
 * in range), solid Low and High guides in the main chart's guide colours, no labels, transparent background. The
 * scale is {@link NotificationScale}; a guide outside it is not drawn, the colour tells which one is shown.
 */
final class NotificationChart {

    private static final float DOT_RADIUS_DP = 2f;
    private static final float GUIDE_DP = 1.5f;
    private static final float INSET_DP = 4f;

    private NotificationChart() {
    }

    /**
     * Renders the chart. Callers wrap this in {@link NeverThrow}.
     *
     * @param themed   themed context (DayNight, so colours follow the system scheme)
     * @param readings readings (any order; outside the window ignored)
     * @param from     window start
     * @param to       window end
     * @param lowMgdl  Low threshold
     * @param highMgdl High threshold
     * @param widthPx  bitmap width
     * @param heightPx bitmap height
     * @return a new ARGB_8888 bitmap
     */
    static Bitmap render(Context themed, List<Reading> readings, Instant from, Instant to, int lowMgdl,
                         int highMgdl, int widthPx, int heightPx) {
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        GlucosePalette palette = GlucoseRendering.palette(themed);
        float density = themed.getResources().getDisplayMetrics().density;
        float inset = INSET_DP * density;
        float left = inset;
        float right = widthPx - inset;
        float top = inset;
        float bottom = heightPx - inset;

        List<Reading> visible = MiniChartGeometry.visible(readings, from, to);
        GlucoseScale scale = NotificationScale.of(visible, lowMgdl, highMgdl);

        Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(GUIDE_DP * density);
        if (NotificationScale.showsGuide(scale, lowMgdl)) {
            guide.setColor(themed.getColor(R.color.glass_guide_low));
            float y = scale.y(lowMgdl, top, bottom);
            canvas.drawLine(0, y, widthPx, y, guide);
        }
        if (NotificationScale.showsGuide(scale, highMgdl)) {
            guide.setColor(themed.getColor(R.color.notification_guide_high));
            float y = scale.y(highMgdl, top, bottom);
            canvas.drawLine(0, y, widthPx, y, guide);
        }

        float[] xy = MiniChartGeometry.points(visible, from, to, scale, left, top, right, bottom);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setStyle(Paint.Style.FILL);
        int highColor = themed.getColor(R.color.notification_guide_high);
        for (int i = 0; i < visible.size(); i++) {
            int sgv = visible.get(i).sgv();
            dot.setColor(sgv <= lowMgdl ? palette.low() : sgv >= highMgdl ? highColor : palette.inRange());
            canvas.drawCircle(xy[2 * i], xy[2 * i + 1], DOT_RADIUS_DP * density, dot);
        }
        return bitmap;
    }
}
