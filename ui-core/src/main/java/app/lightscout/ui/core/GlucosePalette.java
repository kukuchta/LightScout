package app.lightscout.ui.core;

/**
 * GIR-10 colours for drawn glucose elements, resolved from a (DayNight) theme by
 * {@link GlucoseRendering#palette} (CP-00A R-12; DS:D-073; Q-55). All values are ARGB colour ints.
 *
 * <p>Container-tone fills do not reach the 3:1 GIR-10 requires for drawn elements, so they come with a stroke role
 * (Q-55): urgent-low and very-high marks are drawn with their container fill plus a 1–1.5 dp stroke in
 * {@link #urgentLowStroke()} / {@link #veryHighStroke()}; the basal band keeps its fill with an edge line in
 * {@link #basalEdge()} (the fill is then exempt).
 *
 * @param inRange         in range: {@code colorPrimary} (charts)
 * @param low             Low: {@code colorError}
 * @param urgentLow       Urgent low fill: {@code colorErrorContainer}
 * @param high            High: extended {@code warning}
 * @param veryHigh        very high (&gt; 250) fill: extended {@code warningContainer}
 * @param threshold       threshold lines: {@code colorOutline}
 * @param marker          entry markers: {@code colorTertiary} (R-77)
 * @param pumpAlert       pump-alert marker: {@code colorError} (R-77)
 * @param basal           basal band fill: {@code colorSecondaryContainer}
 * @param urgentLowStroke Urgent low stroke: {@code colorError}
 * @param veryHighStroke  very high stroke: extended {@code warning}
 * @param basalEdge       basal band edge line: {@code colorSecondary}
 */
public record GlucosePalette(int inRange, int low, int urgentLow, int high, int veryHigh, int threshold, int marker,
                             int pumpAlert, int basal, int urgentLowStroke, int veryHighStroke, int basalEdge) {

    /** Stroke width of urgent-low / very-high marks and the basal edge line (Q-55: 1–1.5 dp). */
    public static final float STROKE_DP = 1.5f;
}
