package app.lightscout.ui.core;

/**
 * Vertical glucose scale of a drawing (CP-00A R-12, R-77); built by {@link GlucoseRendering#scaleFor}.
 *
 * @param minMgdl value at the bottom edge
 * @param maxMgdl value at the top edge (greater than {@code minMgdl})
 */
public record GlucoseScale(int minMgdl, int maxMgdl) {

    /**
     * Validates the range.
     */
    public GlucoseScale {
        if (maxMgdl <= minMgdl) {
            throw new IllegalArgumentException("maxMgdl must be greater than minMgdl");
        }
    }

    /**
     * Maps a glucose value linearly to a y coordinate (screen coordinates grow downwards).
     *
     * @param sgv    glucose in mg/dL
     * @param top    y of {@link #maxMgdl()}
     * @param bottom y of {@link #minMgdl()}
     * @return the y coordinate (outside {@code [top, bottom]} for values outside the scale)
     */
    public float y(int sgv, float top, float bottom) {
        float fraction = (float) (sgv - minMgdl) / (float) (maxMgdl - minMgdl);
        return bottom - fraction * (bottom - top);
    }
}
