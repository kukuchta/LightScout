package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class GlucoseDeltaTest {

    private static final Instant CURRENT_TIME = Instant.parse("2026-10-16T12:00:00Z");
    private static final Reading CURRENT = reading("current", CURRENT_TIME, 145);

    @Test
    public void includesFourAndSixMinuteGapsAndUsesSignedIntegerDelta() {
        assertEquals(Integer.valueOf(25), GlucoseDelta.calculate(CURRENT,
                List.of(reading("four", CURRENT_TIME.minus(Duration.ofMinutes(4)), 120))));
        assertEquals(Integer.valueOf(-25), GlucoseDelta.calculate(CURRENT,
                List.of(reading("six", CURRENT_TIME.minus(Duration.ofMinutes(6)), 170))));
        assertEquals(Integer.valueOf(0), GlucoseDelta.calculate(CURRENT,
                List.of(reading("zero", CURRENT_TIME.minus(Duration.ofMinutes(5)), 145))));
        assertEquals("+25", GlucoseDelta.signedText(25));
        assertEquals("-25", GlucoseDelta.signedText(-25));
        assertEquals("0", GlucoseDelta.signedText(0));
    }

    @Test
    public void rejectsGapsJustOutsideInclusiveWindow() {
        assertNull(GlucoseDelta.calculate(CURRENT, List.of(reading("short",
                CURRENT_TIME.minus(Duration.ofSeconds(239)), 120))));
        assertNull(GlucoseDelta.calculate(CURRENT, List.of(reading("long",
                CURRENT_TIME.minus(Duration.ofSeconds(361)), 120))));
    }

    @Test
    public void nearerPredecessorPreventsSearchingBackForQualifyingGap() {
        assertNull(GlucoseDelta.calculate(CURRENT, List.of(
                reading("older", CURRENT_TIME.minus(Duration.ofMinutes(5)), 100),
                reading("nearer", CURRENT_TIME.minus(Duration.ofMinutes(2)), 110))));
    }

    @Test
    public void currentTimestampAndMissingValuesDoNotFabricateDelta() {
        assertNull(GlucoseDelta.calculate(null, List.of(reading("old",
                CURRENT_TIME.minus(Duration.ofMinutes(5)), 100))));
        assertNull(GlucoseDelta.calculate(CURRENT, null));
        assertNull(GlucoseDelta.calculate(CURRENT, List.of()));
        assertNull(GlucoseDelta.calculate(CURRENT, List.of(reading("same-time", CURRENT_TIME, 100))));
    }

    @Test
    public void selectsTheImmediateDistinctTimestampAmongCandidates() {
        assertEquals(Integer.valueOf(30), GlucoseDelta.calculate(CURRENT, List.of(
                reading("older", CURRENT_TIME.minus(Duration.ofMinutes(6)), 100),
                reading("at-current-time", CURRENT_TIME, 1),
                reading("immediate", CURRENT_TIME.minus(Duration.ofMinutes(4)), 115))));
    }

    private static Reading reading(String id, Instant time, int sgv) {
        return new Reading(id, time, sgv, Trend.FLAT, "synthetic");
    }
}
