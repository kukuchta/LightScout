package app.lightscout.ui.core;

import app.lightscout.domain.model.Trend;

/**
 * Trend to string table (CP-06 §7.1, CP-00A R-82, M3-34): spoken word {@code trend_*} and text glyph
 * {@code trend_arrow_*} (the {@code ImageView} drawables are in {@link TrendIcons}). {@link Trend#UNKNOWN} (and
 * {@code null}) has the word "trend unknown" and no glyph (0).
 */
final class TrendResources {

    private TrendResources() {
    }

    /**
     * Spoken trend word.
     *
     * @param t trend, {@code null} = unknown
     * @return string resource id
     */
    static int word(Trend t) {
        switch (t == null ? Trend.UNKNOWN : t) {
            case DOUBLE_UP:
                return R.string.trend_double_up;
            case SINGLE_UP:
                return R.string.trend_single_up;
            case FORTY_FIVE_UP:
                return R.string.trend_forty_five_up;
            case FLAT:
                return R.string.trend_flat;
            case FORTY_FIVE_DOWN:
                return R.string.trend_forty_five_down;
            case SINGLE_DOWN:
                return R.string.trend_single_down;
            case DOUBLE_DOWN:
                return R.string.trend_double_down;
            default:
                return R.string.trend_unknown;
        }
    }

    /**
     * Arrow glyph for text surfaces (S-10, S-11, S-12).
     *
     * @param t trend, {@code null} = unknown
     * @return string resource id, or 0 for unknown (no glyph)
     */
    static int arrow(Trend t) {
        switch (t == null ? Trend.UNKNOWN : t) {
            case DOUBLE_UP:
                return R.string.trend_arrow_double_up;
            case SINGLE_UP:
                return R.string.trend_arrow_single_up;
            case FORTY_FIVE_UP:
                return R.string.trend_arrow_forty_five_up;
            case FLAT:
                return R.string.trend_arrow_flat;
            case FORTY_FIVE_DOWN:
                return R.string.trend_arrow_forty_five_down;
            case SINGLE_DOWN:
                return R.string.trend_arrow_single_down;
            case DOUBLE_DOWN:
                return R.string.trend_arrow_double_down;
            default:
                return 0;
        }
    }
}
