package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

/** DCR-37: scale of the notification chart. */
public class NotificationScaleTest {

    private static final Instant T = Instant.parse("2026-11-17T20:00:00Z");

    @Test
    public void rangeIsMinMinusTwentyToMaxPlusTwenty() {
        GlucoseScale s = NotificationScale.of(readings(100, 160, 130), 70, 180);
        assertEquals(80, s.minMgdl());
        assertEquals(180, s.maxMgdl());
    }

    @Test
    public void aFlatHourIsCentredInTheMinimumSpan() {
        GlucoseScale s = NotificationScale.of(readings(120, 121, 119), 70, 180);
        assertEquals(60, s.maxMgdl() - s.minMgdl());
        assertTrue(s.minMgdl() < 119 && s.maxMgdl() > 121);
        assertEquals(120, (s.minMgdl() + s.maxMgdl()) / 2);
    }

    @Test
    public void aSpanOfExactlyTheMinimumIsNotWidened() {
        GlucoseScale s = NotificationScale.of(readings(100, 120), 70, 180);
        assertEquals(80, s.minMgdl());
        assertEquals(140, s.maxMgdl());
    }

    @Test
    public void anEmptyChartIsCentredOnTheGuides() {
        GlucoseScale s = NotificationScale.of(List.of(), 70, 180);
        assertEquals(50, s.minMgdl());
        assertEquals(200, s.maxMgdl());
    }

    @Test
    public void guidesOutsideTheRangeAreNotShown() {
        GlucoseScale allHigh = NotificationScale.of(readings(240, 260), 70, 180);
        assertFalse(NotificationScale.showsGuide(allHigh, 70));
        assertFalse(NotificationScale.showsGuide(allHigh, 180));
        GlucoseScale nearHigh = NotificationScale.of(readings(170, 190), 70, 180);
        assertTrue(NotificationScale.showsGuide(nearHigh, 180));
        assertFalse(NotificationScale.showsGuide(nearHigh, 70));
        GlucoseScale spanning = NotificationScale.of(readings(60, 200), 70, 180);
        assertTrue(NotificationScale.showsGuide(spanning, 70));
        assertTrue(NotificationScale.showsGuide(spanning, 180));
    }

    @Test
    public void aGuideOnTheEdgeIsNotShown() {
        GlucoseScale s = NotificationScale.of(readings(100, 160), 80, 180);
        assertEquals(80, s.minMgdl());
        assertEquals(180, s.maxMgdl());
        assertFalse(NotificationScale.showsGuide(s, 80));
        assertFalse(NotificationScale.showsGuide(s, 180));
    }

    private static List<Reading> readings(int... values) {
        Reading[] out = new Reading[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = new Reading("r" + i, T.plusSeconds(i * 300L), values[i], Trend.FLAT, "u");
        }
        return List.of(out);
    }
}
