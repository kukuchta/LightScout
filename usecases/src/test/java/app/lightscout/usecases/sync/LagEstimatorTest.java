package app.lightscout.usecases.sync;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.OptionalInt;
import org.junit.Test;

/** D-152: the lag is what the server needed, bounded, and robust against the tail. */
public class LagEstimatorTest {

    @Test
    public void sampleIsServerTimeMinusReadingTime() {
        assertEquals(OptionalInt.of(34), LagEstimator.sample(1_000_000L, 1_034_431L));
        assertEquals(OptionalInt.of(0), LagEstimator.sample(1_000_000L, 1_000_000L));
    }

    @Test
    public void skewAndCatchUpUploadsGiveNoSample() {
        assertEquals(OptionalInt.empty(), LagEstimator.sample(1_000_000L, 999_999L));
        assertEquals(OptionalInt.empty(), LagEstimator.sample(1_000_000L, 1_000_000L + 600_001L));
        assertEquals(OptionalInt.of(600), LagEstimator.sample(1_000_000L, 1_000_000L + 600_000L));
    }

    @Test
    public void keepsTheLastTwelveSamples() {
        List<Integer> recent = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 99), LagEstimator.addSamples(recent, List.of(99)));
    }

    @Test
    public void scheduleLagIsTheSeventyFifthPercentileBoundedTo15To180() {
        assertEquals(60, LagEstimator.median(List.of(), 60));
        assertEquals(15, LagEstimator.median(List.of(3, 4, 5), 60));
        assertEquals(180, LagEstimator.median(List.of(400, 500, 600), 60));
        // twelve samples: nearest rank 9 of 12 -> the 9th smallest
        assertEquals(40, LagEstimator.median(List.of(30, 31, 32, 33, 34, 35, 36, 37, 40, 90, 95, 100), 60));
        // a few slow arrivals do not drag the value (the median would be 35)
        assertEquals(36, LagEstimator.median(List.of(30, 31, 32, 33, 34, 35, 36, 36, 36, 36, 60, 120), 60));
    }
}
