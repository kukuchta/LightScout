package app.lightscout.ui.core;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.IconCompat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import app.lightscout.domain.model.Reading;

/**
 * CMP-32 glucose rendering primitives (CP-06 §8.2; CP-00A R-12, R-77, R-81; ADR-013, ADR-050).
 *
 * <p>Custom {@code Canvas} / {@code Bitmap} drawing only. Bitmap rendering touches no View and may run off the main
 * thread (CP-00 §4). {@link #statusNumberIcon} and {@link #renderMiniChart} never throw (06 §11 F4).
 */
public final class GlucoseRendering {

    private GlucoseRendering() {
    }

    /**
     * Renders the bitmap of a status-bar number; package-private seam so a failure can be injected in tests.
     */
    interface NumberBitmapSource {

        /**
         * Renders the text.
         *
         * @param c    context
         * @param text the number or "—"
         * @return the bitmap
         */
        Bitmap render(Context c, String text);
    }

    /**
     * Y range for the visible readings (R-77): fixed 40–250 mg/dL, extended to the data (minimum rounded down to
     * 10, maximum rounded up to 50), limits 0–600; never zooms inward.
     *
     * @param visible readings in view (may be empty)
     * @return the scale
     */
    public static GlucoseScale scaleFor(List<Reading> visible) {
        return ScaleRule.of(visible);
    }

    /**
     * Resolves the GIR-10 drawing colours from a (DayNight) themed context; roles missing from the theme fall back
     * to the LightScout colour resources of the context's configuration.
     *
     * @param themed context carrying {@code Theme.LightScout} (or a theme derived from it)
     * @return the palette
     */
    public static GlucosePalette palette(Context themed) {
        return new GlucosePalette(
                ThemeValues.color(themed, androidx.appcompat.R.attr.colorPrimary,
                        themed.getColor(R.color.md_theme_primary)),
                ThemeValues.color(themed, androidx.appcompat.R.attr.colorError,
                        themed.getColor(R.color.md_theme_error)),
                ThemeValues.color(themed, com.google.android.material.R.attr.colorErrorContainer,
                        themed.getColor(R.color.md_theme_errorContainer)),
                ThemeValues.color(themed, R.attr.warning, themed.getColor(R.color.md_theme_warning)),
                ThemeValues.color(themed, R.attr.warningContainer,
                        themed.getColor(R.color.md_theme_warningContainer)),
                ThemeValues.color(themed, com.google.android.material.R.attr.colorOutline,
                        themed.getColor(R.color.md_theme_outline)),
                ThemeValues.color(themed, com.google.android.material.R.attr.colorTertiary,
                        themed.getColor(R.color.md_theme_tertiary)),
                ThemeValues.color(themed, androidx.appcompat.R.attr.colorError,
                        themed.getColor(R.color.md_theme_error)),
                ThemeValues.color(themed, com.google.android.material.R.attr.colorSecondaryContainer,
                        themed.getColor(R.color.md_theme_secondaryContainer)),
                ThemeValues.color(themed, androidx.appcompat.R.attr.colorError,
                        themed.getColor(R.color.md_theme_error)),
                ThemeValues.color(themed, R.attr.warning, themed.getColor(R.color.md_theme_warning)),
                ThemeValues.color(themed, com.google.android.material.R.attr.colorSecondary,
                        themed.getColor(R.color.md_theme_secondary)));
    }

    /**
     * Renders the S-12 mini chart (C-S12-04): reading line, dashed threshold lines, start / end time labels, no
     * other axis labels; colours from the given themed context (light / dark per its uiMode, M3-35). Never throws:
     * on failure a blank (transparent) bitmap of the requested size is returned. Labels use the phone's current
     * zone ({@link ZoneId#systemDefault()}).
     *
     * @param themed   context with the widget DayNight theme
     * @param readings readings (typically the last 3 h; outside {@code [from, to]} ignored)
     * @param from     window start
     * @param to       window end
     * @param lowMgdl  low threshold line
     * @param highMgdl high threshold line
     * @param widthPx  bitmap width (values below 1 count as 1)
     * @param heightPx bitmap height (values below 1 count as 1)
     * @return the bitmap, never {@code null}
     */
    public static Bitmap renderMiniChart(Context themed, List<Reading> readings, Instant from, Instant to,
                                         int lowMgdl, int highMgdl, int widthPx, int heightPx) {
        return renderMiniChart(themed, readings, from, to, lowMgdl, highMgdl, widthPx, heightPx, true);
    }

    /**
     * Renders a mini chart, optionally omitting both time labels and their reserved space.
     * The original overload retains its start/end labels.
     */
    public static Bitmap renderMiniChart(Context themed, List<Reading> readings, Instant from, Instant to,
                                         int lowMgdl, int highMgdl, int widthPx, int heightPx,
                                         boolean showTimeLabels) {
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        return NeverThrow.orElse(
                () -> MiniChart.render(themed, readings, from, to, lowMgdl, highMgdl, w, h,
                        ZoneId.systemDefault(), showTimeLabels),
                () -> blank(w, h));
    }

    /**
     * Renders the S-10 notification chart (DCR-37): dots, solid Low / High guides in the main chart's colours,
     * a scale from the lowest reading - 20 to the highest + 20 mg/dL.
     */
    public static Bitmap renderNotificationChart(Context themed, List<Reading> readings, Instant from, Instant to,
                                                 int lowMgdl, int highMgdl, int widthPx, int heightPx) {
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        return NeverThrow.orElse(
                () -> NotificationChart.render(themed, readings, from, to, lowMgdl, highMgdl, w, h),
                () -> blank(w, h));
    }

    /**
     * Renders the S-10 status-bar number icon (C-S10-01, ADR-050): white glyphs on transparent; {@code null}
     * (stale / no reading) gives "—". Never throws: on any render failure the resource icon
     * {@code ic_stat_lightscout} is returned (06 §11 F4).
     *
     * <p>The context must not be {@code null} (IR-63): the resource fallback needs it; the never-throw guarantee
     * covers rendering, not a missing context.
     *
     * @param c         non-null context
     * @param sgvOrNull glucose in mg/dL, or {@code null} for "—"
     * @return the icon
     */
    public static IconCompat statusNumberIcon(@NonNull Context c, Integer sgvOrNull) {
        return statusNumberIcon(c, sgvOrNull, NumberIcon::render);
    }

    /**
     * {@link #statusNumberIcon(Context, Integer)} with an explicit bitmap source.
     *
     * @param c         context
     * @param sgvOrNull glucose in mg/dL, or {@code null} for "—"
     * @param source    renders the bitmap
     * @return the icon
     */
    static IconCompat statusNumberIcon(@NonNull Context c, Integer sgvOrNull, NumberBitmapSource source) {
        return NeverThrow.orElse(
                () -> IconCompat.createWithBitmap(
                        source.render(c, numberText(sgvOrNull, c.getString(STALE_GLYPH)))),
                () -> IconCompat.createWithResource(c, R.drawable.ic_stat_lightscout));
    }

    /** String resource of the stale / empty glyph on the number icon: {@code s01_reading_empty} „—” (R-81, R-82). */
    static final int STALE_GLYPH = R.string.s01_reading_empty;

    /**
     * Text drawn on the number icon.
     *
     * @param sgvOrNull  glucose, or {@code null} when stale / no reading
     * @param staleGlyph text of {@link #STALE_GLYPH}
     * @return the number or the stale glyph
     */
    static String numberText(Integer sgvOrNull, String staleGlyph) {
        return sgvOrNull == null ? staleGlyph : Integer.toString(sgvOrNull);
    }

    private static Bitmap blank(int w, int h) {
        try {
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}
