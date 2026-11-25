package app.lightscout.domain.settings;

/**
 * Target range for statistics only, independent of the alarm thresholds (G-26, CP-00A R-3).
 * Both bounds are inclusive (R-63).
 *
 * @param lowMgdl  lower bound in mg/dL (default 70)
 * @param highMgdl upper bound in mg/dL (default 180)
 */
public record TargetRange(int lowMgdl, int highMgdl) {
}
