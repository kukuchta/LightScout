package app.lightscout.domain.statistics;

import app.lightscout.domain.entry.ReadingRules;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.settings.TargetRange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Statistics calculation (CMP-05, IF-04; design S-03, S-01 C-S01-18; DS:D-031, DS:D-035, DS:A-06, DS:A-16, DS:A-20;
 * CP-00A R-63).
 *
 * <p>Readings in {@code [start, end)} with {@code sgv ≥ 1} (M-10) pass through
 * {@link ReadingRules#displayable} (DR-1). Bands: very low {@code < 54}; low {@code 54 ≤ sgv < range.low};
 * in range {@code range.low ≤ sgv ≤ range.high}; high {@code range.high < sgv ≤ 250}; very high {@code > 250}.
 * CV uses the population SD. GMI = 3.31 + 0.02392 × mean. No rounding (presentation rounds).
 */
public final class StatisticsCalculator {

    private static final int VERY_LOW_BELOW = 54;
    private static final int VERY_HIGH_ABOVE = 250;
    private static final double PARTIAL_BELOW = 0.70;
    private static final double GMI_INTERCEPT = 3.31;
    private static final double GMI_SLOPE = 0.02392;
    private static final double PERCENT = 100.0;

    /**
     * Creates the stateless rule component.
     */
    public StatisticsCalculator() {
        // no state
    }

    /**
     * Computes the statistics of a period.
     *
     * @param readings readings in any order (may include readings outside the period)
     * @param period   the period
     * @param range    the target range
     * @return the statistics; all metrics 0 when {@code empty}
     */
    public Statistics compute(List<Reading> readings, Period period, TargetRange range) {
        List<Reading> inPeriod = new ArrayList<>();
        for (Reading r : readings) {
            if (r != null && r.time() != null && r.sgv() >= 1 && inPeriod(r.time(), period)) {
                inPeriod.add(r);
            }
        }
        List<Reading> counted = ReadingRules.displayable(inPeriod, period.end());
        int n = counted.size();
        long expected = period.expectedReadings();
        double coverage = expected <= 0 ? 0.0 : Math.min(1.0, (double) n / expected);
        boolean partial = coverage < PARTIAL_BELOW;
        if (n == 0) {
            return new Statistics(0, expected, coverage, true, partial, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        int veryLow = 0;
        int low = 0;
        int inRange = 0;
        int high = 0;
        int veryHigh = 0;
        double sum = 0;
        for (Reading r : counted) {
            int sgv = r.sgv();
            sum += sgv;
            if (sgv < VERY_LOW_BELOW) {
                veryLow++;
            } else if (sgv < range.lowMgdl()) {
                low++;
            } else if (sgv <= range.highMgdl()) {
                inRange++;
            } else if (sgv <= VERY_HIGH_ABOVE) {
                high++;
            } else {
                veryHigh++;
            }
        }
        double mean = sum / n;
        double squares = 0;
        for (Reading r : counted) {
            double d = r.sgv() - mean;
            squares += d * d;
        }
        double sd = Math.sqrt(squares / n);
        double cv = sd / mean * PERCENT;
        double gmi = GMI_INTERCEPT + GMI_SLOPE * mean;
        return new Statistics(n, expected, coverage, false, partial,
                pct(veryLow, n), pct(low, n), pct(inRange, n), pct(high, n), pct(veryHigh, n),
                mean, cv, gmi);
    }

    private static boolean inPeriod(Instant t, Period p) {
        return !t.isBefore(p.start()) && t.isBefore(p.end());
    }

    private static double pct(int count, int total) {
        return count * PERCENT / total;
    }
}
