package app.lightscout.ui.core;

import java.util.List;

import app.lightscout.domain.model.Reading;

/**
 * Vertical scale of the S-10 notification chart (DCR-37): the readings' own range padded by
 * {@link #PADDING_MGDL} on both sides, so the chart is not flattened by empty space above and below, but never
 * narrower than {@link #MIN_SPAN_MGDL}, so that an almost flat hour does not blow tiny differences up.
 * The Low / High guides are not part of the range: they are drawn only when they fall inside it.
 */
final class NotificationScale {

    /** Space kept above the highest and below the lowest reading, in mg/dL. */
    static final int PADDING_MGDL = 20;

    /** Smallest range shown, in mg/dL (a flat hour is centred in a range of this size). */
    static final int MIN_SPAN_MGDL = 60;

    private NotificationScale() {
    }

    /**
     * Builds the scale.
     *
     * @param visible  the readings of the window; may be empty
     * @param lowMgdl  Low threshold, used only to centre an empty chart
     * @param highMgdl High threshold, used only to centre an empty chart
     * @return the scale (never degenerate)
     */
    static GlucoseScale of(List<Reading> visible, int lowMgdl, int highMgdl) {
        if (visible == null || visible.isEmpty()) {
            return new GlucoseScale(lowMgdl - PADDING_MGDL, Math.max(highMgdl, lowMgdl + 1) + PADDING_MGDL);
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Reading r : visible) {
            min = Math.min(min, r.sgv());
            max = Math.max(max, r.sgv());
        }
        int lower = min - PADDING_MGDL;
        int upper = max + PADDING_MGDL;
        int missing = MIN_SPAN_MGDL - (upper - lower);
        if (missing > 0) {
            lower -= missing / 2;
            upper += missing - missing / 2;
        }
        return new GlucoseScale(lower, upper);
    }

    /**
     * Tells whether a guide lies inside the scale and is therefore drawn.
     *
     * @param scale the scale
     * @param value the guide value in mg/dL
     * @return {@code true} when strictly between the scale's edges
     */
    static boolean showsGuide(GlucoseScale scale, int value) {
        return value > scale.minMgdl() && value < scale.maxMgdl();
    }
}
