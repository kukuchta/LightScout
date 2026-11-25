package app.lightscout.ui.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

/**
 * Pure parts of CMP-32: the R-77 y-range rule, {@link GlucoseScale#y}, the mini-chart input handling
 * (§9 "renderMiniChart" — window filter, ordering, empty input) and the never-throw fallback (§9
 * "statusNumberIcon … injected render failure → resource icon, no exception").
 */
public class RenderingRulesTest {

    private static final Instant TO = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant FROM = TO.minus(Duration.ofHours(3));

    private static Reading reading(Instant t, int sgv) {
        return new Reading("id-" + t.toEpochMilli(), t, sgv, Trend.FLAT, "test");
    }

    @Test
    public void scaleIsFixedFortyToTwoHundredFiftyWithoutData() {
        assertEquals(new GlucoseScale(40, 250), GlucoseRendering.scaleFor(Collections.emptyList()));
        assertEquals(new GlucoseScale(40, 250), GlucoseRendering.scaleFor(null));
        assertEquals(new GlucoseScale(40, 250),
                GlucoseRendering.scaleFor(List.of(reading(TO, 120), reading(TO, 250))));
    }

    @Test
    public void scaleExtendsToDataRoundedAndLimited() {
        assertEquals(new GlucoseScale(30, 350), ScaleRule.of(List.of(reading(TO, 39), reading(TO, 301))));
        assertEquals(new GlucoseScale(0, 600), ScaleRule.of(List.of(reading(TO, -5), reading(TO, 640))));
        assertEquals(new GlucoseScale(40, 450), ScaleRule.of(List.of(reading(TO, 401))));
        assertEquals(new GlucoseScale(40, 250), ScaleRule.of(Arrays.asList(null, reading(TO, 250))));
        assertEquals(new GlucoseScale(40, 300), ScaleRule.of(Arrays.asList(null, reading(TO, 251))));
    }

    @Test
    public void scaleMapsLinearly() {
        GlucoseScale s = new GlucoseScale(40, 300);
        assertEquals(100f, s.y(40, 0f, 100f), 1e-4);
        assertEquals(0f, s.y(300, 0f, 100f), 1e-4);
        assertEquals(50f, s.y(170, 0f, 100f), 1e-4);
        assertThrows(IllegalArgumentException.class, () -> new GlucoseScale(100, 100));
    }

    @Test
    public void miniChartKeepsOnlyTheWindowSortedByTime() {
        List<Reading> input = new ArrayList<>();
        input.add(reading(TO.minusSeconds(60), 150));
        input.add(reading(FROM.minusSeconds(1), 90));          // before the window
        input.add(null);
        input.add(reading(FROM, 100));
        input.add(reading(TO.plusSeconds(1), 200));            // after the window
        input.add(reading(TO, 160));
        List<Reading> visible = MiniChartGeometry.visible(input, FROM, TO);
        assertEquals(3, visible.size());
        assertEquals(FROM, visible.get(0).time());
        assertEquals(TO, visible.get(2).time());
        assertTrue(MiniChartGeometry.visible(null, FROM, TO).isEmpty());
    }

    @Test
    public void miniChartPointsSpanThePlot() {
        List<Reading> visible = List.of(reading(FROM, 40), reading(FROM.plus(Duration.ofMinutes(90)), 170),
                reading(TO, 300));
        float[] xy = MiniChartGeometry.points(visible, FROM, TO, new GlucoseScale(40, 300), 0f, 0f, 300f, 100f);
        assertArrayEquals(new float[] {0f, 100f, 150f, 50f, 300f, 0f}, xy, 1e-3f);
        assertEquals(0, MiniChartGeometry.points(List.of(), FROM, TO, new GlucoseScale(40, 300),
                0f, 0f, 300f, 100f).length);
    }

    @Test
    public void staleNumberIconGlyphIsReadingEmptyDash() {
        assertEquals("s01_reading_empty", TrendResourcesTest.name(R.string.class, GlucoseRendering.STALE_GLYPH));
        String dash = ResXml.string("s01_reading_empty");
        assertEquals("—", dash);
        assertEquals(dash, GlucoseRendering.numberText(null, dash));
        assertEquals("142", GlucoseRendering.numberText(142, dash));
        assertEquals("39", GlucoseRendering.numberText(39, dash));
    }

    @Test
    public void neverThrowReturnsPrimaryOrFallback() {
        Object primary = new Object();
        Object fallback = new Object();
        assertSame(primary, NeverThrow.orElse(() -> primary, () -> fallback));
        assertSame(fallback, NeverThrow.orElse(() -> {
            throw new IllegalStateException("render failed");
        }, () -> fallback));
        assertSame(fallback, NeverThrow.orElse(() -> {
            throw new OutOfMemoryError("bitmap");
        }, () -> fallback));
        assertSame(fallback, NeverThrow.orElse(() -> null, () -> fallback));
    }
}
