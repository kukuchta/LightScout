package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.settings.TargetRange;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.statistics.PeriodFactory;
import app.lightscout.domain.statistics.Statistics;
import app.lightscout.domain.statistics.StatisticsCalculator;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** CP-12 TIR card rules: coverage threshold, statistics mapping and independently rounded legend values. */
public class HomeDistributionViewTest {

    private static final Instant END = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    public void coverageBelowSeventyPercentUsesNotEnoughDataState() {
        Period period = PeriodFactory.lastDays(1, END);
        Statistics partial = calculator().compute(readings(201), period, new TargetRange(70, 180));
        assertTrue(partial.partial());
        assertFalse(HomeDistributionView.values(partial).enoughData());
    }

    @Test
    public void sufficientCoverageMapsTirTbrTarWithHalfUpRounding() {
        Period period = PeriodFactory.lastDays(1, END);
        Statistics complete = calculator().compute(readings(202), period, new TargetRange(70, 180));
        HomeDistributionView.Values values = HomeDistributionView.values(complete);
        assertTrue(values.enoughData());
        assertEquals(50, values.inRange());
        assertEquals(0, values.veryLow());
        assertEquals(25, values.low());
        assertEquals(25, values.high());
        assertEquals(0, values.veryHigh());
    }

    @Test
    public void emptyPeriodDoesNotRenderAZeroPercentDonut() {
        Period period = PeriodFactory.lastDays(1, END);
        Statistics empty = calculator().compute(List.of(), period, new TargetRange(70, 180));
        assertFalse(HomeDistributionView.values(empty).enoughData());
    }

    @Test
    public void fiveBandsKeepOriginalOrderAndIndependentHalfUpRounding() {
        Statistics complete = new Statistics(202, 288, 202d / 288, false, false,
                1.5, 2.5, 70.5, 20.5, 4.5, 120, 20, 6.2);
        HomeDistributionView.Values values = HomeDistributionView.values(complete);
        assertEquals(2, values.veryLow());
        assertEquals(3, values.low());
        assertEquals(71, values.inRange());
        assertEquals(21, values.high());
        assertEquals(5, values.veryHigh());
    }

    private static StatisticsCalculator calculator() {
        return new StatisticsCalculator();
    }

    private static List<Reading> readings(int count) {
        List<Reading> readings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int value = i < 101 ? 120 : i < 151 ? 60 : 200;
            readings.add(new Reading("d-" + i, END.minus(Duration.ofMinutes(i * 5L + 1)),
                    value, Trend.FLAT, "synthetic"));
        }
        return readings;
    }
}
