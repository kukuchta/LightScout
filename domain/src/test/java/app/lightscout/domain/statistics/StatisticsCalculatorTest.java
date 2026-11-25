package app.lightscout.domain.statistics;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.settings.TargetRange;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TST-01 statistics rows STAT-1…3, STAT-5, STAT-6, STAT-8 (CP-01 §8.5 / §9; CP-00A R-63).
 */
public class StatisticsCalculatorTest {

    private static final double EPS = 1e-9;
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final TargetRange DEFAULT_RANGE = new TargetRange(70, 180);

    private final StatisticsCalculator calculator = new StatisticsCalculator();
    private final Period yesterday = PeriodFactory.yesterday(ZonedDateTime.of(2026, 10, 15, 10, 0, 0, 0, WARSAW));

    private static List<Reading> series(Period p, int count, int sgv) {
        List<Reading> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new Reading("s" + i, p.start().plus(Duration.ofMinutes(5L * i)), sgv, Trend.FLAT, "t"));
        }
        return list;
    }

    private static List<Reading> values(Period p, int... sgvs) {
        List<Reading> list = new ArrayList<>();
        for (int i = 0; i < sgvs.length; i++) {
            list.add(new Reading("v" + i, p.start().plus(Duration.ofMinutes(5L * i)), sgvs[i], Trend.FLAT, "t"));
        }
        return list;
    }

    @Test
    public void stat1_288At100_fullCoverage() {
        Statistics s = calculator.compute(series(yesterday, 288, 100), yesterday, DEFAULT_RANGE);
        assertEquals(288, s.readingCount());
        assertEquals(288, s.expectedCount());
        assertEquals(1.0, s.coverage(), EPS);
        assertEquals(100.0, s.tirPct(), EPS);
        assertEquals(100.0, s.meanMgdl(), EPS);
        assertEquals(0.0, s.cvPct(), EPS);
        assertEquals(5.702, s.gmiPct(), EPS);
        assertFalse(s.partial());
        assertFalse(s.empty());
    }

    @Test
    public void stat2_mean150_gmi() {
        Statistics s = calculator.compute(series(yesterday, 288, 150), yesterday, DEFAULT_RANGE);
        assertEquals(150.0, s.meanMgdl(), EPS);
        assertEquals(3.31 + 0.02392 * 150, s.gmiPct(), EPS);
        assertEquals(6.898, s.gmiPct(), EPS);
    }

    @Test
    public void stat3_partialBoundary_andEmpty() {
        Statistics s201 = calculator.compute(series(yesterday, 201, 100), yesterday, DEFAULT_RANGE);
        assertEquals(201.0 / 288, s201.coverage(), EPS);
        assertTrue(s201.partial());

        Statistics s202 = calculator.compute(series(yesterday, 202, 100), yesterday, DEFAULT_RANGE);
        assertEquals(202.0 / 288, s202.coverage(), EPS);
        assertFalse(s202.partial());

        Statistics none = calculator.compute(List.of(), yesterday, DEFAULT_RANGE);
        assertTrue(none.empty());
        assertEquals(0, none.readingCount());
        assertEquals(288, none.expectedCount());
        assertEquals(0.0, none.coverage(), EPS);
    }

    @Test
    public void stat5_bands_veryLowAndVeryHigh_countInTbrAndTar() {
        Statistics s = calculator.compute(values(yesterday, 50, 260, 100), yesterday, DEFAULT_RANGE);
        double third = 100.0 / 3;
        assertEquals(third, s.veryLowPct(), EPS);
        assertEquals(third, s.below54Pct(), EPS);
        assertEquals(third, s.tbrPct(), EPS);
        assertEquals(third, s.veryHighPct(), EPS);
        assertEquals(third, s.above250Pct(), EPS);
        assertEquals(third, s.tarPct(), EPS);
        assertEquals(third, s.tirPct(), EPS);
    }

    @Test
    public void stat5_targetRangeChangesTirButNot54And250Bands() {
        List<Reading> readings = values(yesterday, 75, 170, 50, 260);
        Statistics def = calculator.compute(readings, yesterday, DEFAULT_RANGE);
        Statistics narrow = calculator.compute(readings, yesterday, new TargetRange(80, 160));
        assertEquals(50.0, def.tirPct(), EPS);
        assertEquals(0.0, narrow.tirPct(), EPS);
        assertEquals(25.0, narrow.lowPct(), EPS);
        assertEquals(25.0, narrow.highPct(), EPS);
        assertEquals(def.below54Pct(), narrow.below54Pct(), EPS);
        assertEquals(def.above250Pct(), narrow.above250Pct(), EPS);
        assertEquals(25.0, narrow.below54Pct(), EPS);
        assertEquals(25.0, narrow.above250Pct(), EPS);
    }

    @Test
    public void r63_boundaries_inclusiveRange_54and250() {
        // 53 very low · 54 low · 70 in · 180 in · 181 high · 250 high · 251 very high
        Statistics s = calculator.compute(values(yesterday, 53, 54, 70, 180, 181, 250, 251), yesterday, DEFAULT_RANGE);
        double one = 100.0 / 7;
        assertEquals(one, s.veryLowPct(), EPS);
        assertEquals(one, s.lowPct(), EPS);
        assertEquals(2 * one, s.inRangePct(), EPS);
        assertEquals(2 * one, s.highPct(), EPS);
        assertEquals(one, s.veryHighPct(), EPS);
    }

    @Test
    public void r63_cvUsesPopulationSd() {
        Statistics s = calculator.compute(values(yesterday, 90, 110), yesterday, DEFAULT_RANGE);
        assertEquals(100.0, s.meanMgdl(), EPS);
        assertEquals(10.0, s.cvPct(), EPS);
    }

    @Test
    public void stat6_dr1_readingsUnder60sApartCountOnce() {
        Instant t = yesterday.start().plus(Duration.ofHours(10));
        List<Reading> close = List.of(
                new Reading("a", t, 100, Trend.FLAT, "t"),
                new Reading("b", t.plusSeconds(40), 200, Trend.FLAT, "t"));
        Statistics s1 = calculator.compute(close, yesterday, DEFAULT_RANGE);
        assertEquals(1, s1.readingCount());
        assertEquals(100.0, s1.meanMgdl(), EPS);   // the earlier one is kept

        List<Reading> apart = List.of(
                new Reading("a", t, 100, Trend.FLAT, "t"),
                new Reading("b", t.plusSeconds(60), 200, Trend.FLAT, "t"));
        assertEquals(2, calculator.compute(apart, yesterday, DEFAULT_RANGE).readingCount());
    }

    @Test
    public void stat8_doughnut24h_under70Percent_isNotEnoughData() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        Period last24 = PeriodFactory.lastDays(1, now);
        Statistics s = calculator.compute(series(last24, 200, 120), last24, DEFAULT_RANGE);
        assertTrue(s.partial());
        Statistics enough = calculator.compute(series(last24, 288, 120), last24, DEFAULT_RANGE);
        assertFalse(enough.partial());
    }

    @Test
    public void m10_sgvBelow1NotAReading_sgv1Counts() {
        Statistics s = calculator.compute(values(yesterday, 0, 1), yesterday, DEFAULT_RANGE);
        assertEquals(1, s.readingCount());
        assertEquals(100.0, s.veryLowPct(), EPS);
    }

    @Test
    public void readingsOutsidePeriod_areIgnored() {
        List<Reading> readings = List.of(
                new Reading("before", yesterday.start().minusSeconds(1), 100, Trend.FLAT, "t"),
                new Reading("start", yesterday.start(), 100, Trend.FLAT, "t"),
                new Reading("end", yesterday.end(), 100, Trend.FLAT, "t"));
        assertEquals(1, calculator.compute(readings, yesterday, DEFAULT_RANGE).readingCount());
    }
}
