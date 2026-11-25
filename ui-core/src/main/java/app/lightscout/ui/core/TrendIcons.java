package app.lightscout.ui.core;

import app.lightscout.domain.model.Trend;

/**
 * Trend arrow vector drawables for {@code ImageView}s (CP-00A R-82: S-01 C-S01-09, S-09). The order follows the
 * design glyphs ↑↑ ↑ ↗ → ↘ ↓ ↓↓ for DoubleUp … DoubleDown; {@link Trend#UNKNOWN} has no icon. The caller sets
 * {@code contentDescription} from {@link Formatters#trendWords} (M3-34) and hides the view for 0.
 */
public final class TrendIcons {

    private TrendIcons() {
    }

    /**
     * Returns the arrow drawable of a trend: the line style "B1" (DCR-37), white, tinted by the caller.
     *
     * @param t trend, {@code null} = unknown
     * @return drawable resource id, or 0 for unknown (no icon)
     */
    public static int drawableFor(Trend t) {
        switch (t == null ? Trend.UNKNOWN : t) {
            case DOUBLE_UP:
                return R.drawable.ic_trend_double_up;
            case SINGLE_UP:
                return R.drawable.ic_trend_single_up;
            case FORTY_FIVE_UP:
                return R.drawable.ic_trend_forty_five_up;
            case FLAT:
                return R.drawable.ic_trend_flat;
            case FORTY_FIVE_DOWN:
                return R.drawable.ic_trend_forty_five_down;
            case SINGLE_DOWN:
                return R.drawable.ic_trend_single_down;
            case DOUBLE_DOWN:
                return R.drawable.ic_trend_double_down;
            default:
                return 0;
        }
    }
}
