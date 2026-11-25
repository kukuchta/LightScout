package app.lightscout.domain.statistics;

/**
 * Statistics of one period (IF-04, design S-03, CP-00A R-7, R-63). Unrounded; rounding is presentation.
 * When {@code empty} every percentage and metric is {@code 0} and must be shown as „—”.
 *
 * @param readingCount  deduplicated readings in the period
 * @param expectedCount expected readings (1 per 5 min)
 * @param coverage      {@code readingCount ÷ expectedCount}, capped to 0..1
 * @param empty         {@code readingCount == 0}
 * @param partial       {@code coverage < 0.70} (DS:A-16)
 * @param veryLowPct    % below 54 mg/dL
 * @param lowPct        % from 54 to below the target-range low
 * @param inRangePct    % within the target range (inclusive)
 * @param highPct       % above the target-range high up to 250
 * @param veryHighPct   % above 250 mg/dL
 * @param meanMgdl      mean glucose
 * @param cvPct         coefficient of variation (population SD ÷ mean × 100)
 * @param gmiPct        GMI = 3.31 + 0.02392 × mean (DS:D-035)
 */
public record Statistics(int readingCount, long expectedCount, double coverage,
                         boolean empty, boolean partial,
                         double veryLowPct, double lowPct, double inRangePct, double highPct, double veryHighPct,
                         double meanMgdl, double cvPct, double gmiPct) {

    /**
     * Returns time in range.
     *
     * @return TIR %
     */
    public double tirPct() {
        return inRangePct;
    }

    /**
     * Returns time below range (very low + low).
     *
     * @return TBR %
     */
    public double tbrPct() {
        return veryLowPct + lowPct;
    }

    /**
     * Returns the part of TBR below 54 mg/dL.
     *
     * @return % below 54
     */
    public double below54Pct() {
        return veryLowPct;
    }

    /**
     * Returns time above range (high + very high).
     *
     * @return TAR %
     */
    public double tarPct() {
        return highPct + veryHighPct;
    }

    /**
     * Returns the part of TAR above 250 mg/dL.
     *
     * @return % above 250
     */
    public double above250Pct() {
        return veryHighPct;
    }
}
