package app.lightscout.ui.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import app.lightscout.domain.model.Reading;

/**
 * Draws the S-12 mini chart (C-S12-04, M3-35): reading line in the in-range role, dashed threshold lines in
 * {@code colorOutline}, no axis labels except the start and end time (Label Small, {@code colorOnSurfaceVariant}),
 * transparent background (the widget layout owns its surface). Colours come from the given themed context, so a
 * light or dark configuration gives the respective scheme.
 */
final class MiniChart {

    private static final float LINE_DP = 2f;
    private static final float THRESHOLD_DP = 1f;
    private static final float DASH_DP = 4f;
    private static final float DOT_DP = 2f;
    private static final float LABEL_GAP_DP = 2f;
    private static final float LABEL_SMALL_SP = 11f;

    private MiniChart() {
    }

    /**
     * Renders the chart. Callers wrap this in {@link NeverThrow}.
     *
     * @param themed   themed context (widget DayNight theme)
     * @param readings readings (any order; outside the window ignored)
     * @param from     window start
     * @param to       window end
     * @param lowMgdl  low threshold
     * @param highMgdl high threshold
     * @param widthPx  bitmap width
     * @param heightPx bitmap height
     * @param zone     zone of the start/end labels
     * @return a new ARGB_8888 bitmap
     */
    static Bitmap render(Context themed, List<Reading> readings, Instant from, Instant to, int lowMgdl,
                         int highMgdl, int widthPx, int heightPx, ZoneId zone, boolean showTimeLabels) {
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        GlucosePalette palette = GlucoseRendering.palette(themed);
        float density = themed.getResources().getDisplayMetrics().density;

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(ThemeValues.color(themed, com.google.android.material.R.attr.colorOnSurfaceVariant,
                themed.getColor(R.color.md_theme_onSurfaceVariant)));
        label.setTextSize(ThemeValues.textSizePx(themed,
                com.google.android.material.R.attr.textAppearanceLabelSmall, LABEL_SMALL_SP));
        Paint.FontMetrics fm = label.getFontMetrics();
        float labelHeight = fm.descent - fm.ascent;
        float plotBottom = showTimeLabels ? heightPx - labelHeight - LABEL_GAP_DP * density
                : heightPx - LINE_DP * density;
        float plotTop = LINE_DP * density;
        float left = LINE_DP * density;
        float right = widthPx - LINE_DP * density;

        List<Reading> visible = MiniChartGeometry.visible(readings, from, to);
        GlucoseScale scale = ScaleRule.of(visible);

        Paint threshold = new Paint(Paint.ANTI_ALIAS_FLAG);
        threshold.setColor(palette.threshold());
        threshold.setStyle(Paint.Style.STROKE);
        threshold.setStrokeWidth(THRESHOLD_DP * density);
        threshold.setPathEffect(new DashPathEffect(new float[] {DASH_DP * density, DASH_DP * density}, 0));
        for (int mgdl : new int[] {lowMgdl, highMgdl}) {
            float y = scale.y(mgdl, plotTop, plotBottom);
            Path p = new Path();
            p.moveTo(0, y);
            p.lineTo(widthPx, y);
            canvas.drawPath(p, threshold);
        }

        float[] xy = MiniChartGeometry.points(visible, from, to, scale, left, plotTop, right, plotBottom);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(palette.inRange());
        line.setStrokeWidth(LINE_DP * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        if (xy.length == 2) {
            canvas.drawCircle(xy[0], xy[1], DOT_DP * density, line);
        } else if (xy.length > 2) {
            Path path = new Path();
            path.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) {
                path.lineTo(xy[i], xy[i + 1]);
            }
            line.setStyle(Paint.Style.STROKE);
            canvas.drawPath(path, line);
        }

        if (showTimeLabels) {
            float baseline = heightPx - fm.descent;
            label.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(Formatters.clockTime(themed, from, zone), 0, baseline, label);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(Formatters.clockTime(themed, to, zone), widthPx, baseline, label);
        }
        return bitmap;
    }
}
