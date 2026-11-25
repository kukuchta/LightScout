package app.lightscout.ui.core;

import java.util.List;

import app.lightscout.domain.model.Reading;

/**
 * Pure y-range rule (CP-00A R-77): fixed 40–250 mg/dL, extended to the visible data (minimum rounded down to 10,
 * maximum rounded up to 50), limits 0–600; never zooms inward.
 */
final class ScaleRule {

    static final int DEFAULT_MIN = 40;
    static final int DEFAULT_MAX = 250;
    static final int LIMIT_MIN = 0;
    static final int LIMIT_MAX = 600;

    private ScaleRule() {
    }

    /**
     * Computes the scale for the visible readings.
     *
     * @param visible readings in view (may be empty; {@code null} elements are ignored)
     * @return the scale
     */
    static GlucoseScale of(List<Reading> visible) {
        int min = DEFAULT_MIN;
        int max = DEFAULT_MAX;
        if (visible != null) {
            for (Reading r : visible) {
                if (r == null) {
                    continue;
                }
                min = Math.min(min, Math.floorDiv(r.sgv(), 10) * 10);
                max = Math.max(max, -Math.floorDiv(-r.sgv(), 50) * 50);
            }
        }
        return new GlucoseScale(Math.max(LIMIT_MIN, min), Math.min(LIMIT_MAX, max));
    }
}
