package app.lightscout.domain.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** The 4-6 minute delta rule shared by Home and the status notification. */
public class ReadingDeltaTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private static Reading reading(long minutesAgo, long extraSeconds, int sgv) {
        return new Reading("r" + minutesAgo + "_" + extraSeconds, NOW.minusSeconds(minutesAgo * 60 + extraSeconds), sgv,
                Trend.FLAT, "dev");
    }

    @Test
    public void gapBoundsAreInclusive() {
        Reading current = reading(0, 0, 120);
        assertNull(ReadingDelta.calculate(current, List.of(reading(3, 59, 100))));
        assertEquals(Integer.valueOf(20), ReadingDelta.calculate(current, List.of(reading(4, 0, 100))));
        assertEquals(Integer.valueOf(20), ReadingDelta.calculate(current, List.of(reading(6, 0, 100))));
        assertNull(ReadingDelta.calculate(current, List.of(reading(6, 1, 100))));
    }

    @Test
    public void signIsCurrentMinusPrevious() {
        assertEquals(Integer.valueOf(-15), ReadingDelta.calculate(reading(0, 0, 100), List.of(reading(5, 0, 115))));
    }

    @Test
    public void nearestPredecessorWinsAndANearerOutOfRangeReadingBlocksOlderOnes() {
        Reading current = reading(0, 0, 120);
        assertEquals(Integer.valueOf(10), ReadingDelta.calculate(current,
                List.of(reading(10, 0, 50), reading(5, 0, 110), reading(8, 0, 60))));
        assertNull(ReadingDelta.calculate(current, List.of(reading(2, 0, 118), reading(5, 0, 110))));
    }

    @Test
    public void ignoresNewerAndNullInputs() {
        Reading current = reading(0, 0, 120);
        assertNull(ReadingDelta.calculate(null, List.of(reading(5, 0, 100))));
        assertNull(ReadingDelta.calculate(current, null));
        assertNull(ReadingDelta.calculate(current, Arrays.asList(null, reading(-5, 0, 100))));
    }
}
