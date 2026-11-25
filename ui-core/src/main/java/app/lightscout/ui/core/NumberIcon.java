package app.lightscout.ui.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * Renders the S-10 status-bar number (C-S10-01, ADR-050): white glyphs on a transparent square bitmap of the
 * standard 24 dp status-bar icon size; the text is scaled down until it fits (3 digits such as "401" or "—").
 */
final class NumberIcon {

    /** Status-bar icon size (platform guideline). */
    static final float ICON_SIZE_DP = 24f;

    /** Share of the icon width / height the text may use. */
    private static final float FILL = 0.96f;

    private NumberIcon() {
    }

    /**
     * Renders the text.
     *
     * @param c    context (display metrics)
     * @param text the number or "—"
     * @return a new ARGB_8888 bitmap
     */
    static Bitmap render(Context c, String text) {
        int size = Math.max(1, Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ICON_SIZE_DP,
                c.getResources().getDisplayMetrics())));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        float scale = Math.min(size * FILL / Math.max(1, bounds.width()),
                size * FILL / Math.max(1, bounds.height()));
        paint.setTextSize(size * scale);
        paint.getTextBounds(text, 0, text.length(), bounds);
        float baseline = size / 2f - bounds.exactCenterY();
        new Canvas(bitmap).drawText(text, size / 2f, baseline, paint);
        return bitmap;
    }
}
