package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;

public class ChartMarkerHitAreasTest {

    @Test
    public void minimumMarkerTargetScalesToAtLeast48Dp() {
        for (float density : new float[] {1f, 2f, 3f}) {
            assertEquals(48f * density, ChartMarkerHitAreas.minimumSizePx(density), 0.01f);
        }
    }

    @Test
    public void overlappingMarkersGroupInEventTimeOrder() {
        Entry later = entry("later", Instant.parse("2026-10-26T12:01:00Z"));
        Entry earlier = entry("earlier", Instant.parse("2026-10-26T12:00:00Z"));
        Entry separate = entry("separate", Instant.parse("2026-10-26T12:02:00Z"));
        ChartMarkerHitAreas.Target first = target(later, 100);
        ChartMarkerHitAreas.Target second = target(earlier, 140);
        ChartMarkerHitAreas.Target third = target(separate, 220);

        assertTrue(first.overlaps(second));
        assertFalse(first.overlaps(third));
        assertEquals(List.of(earlier, later),
                ChartMarkerHitAreas.overlappingEntries(List.of(first, second, third), first));
    }

    @Test
    public void sameTimeCarbsAndBolusUseSeparateLanesAboveTheBasalBand() {
        Instant time = Instant.parse("2026-10-26T12:00:00Z");
        Entry carbs = entry("carbs", time);
        Entry bolus = new Entry("bolus", EntryOrigin.TREATMENTS, EntryType.BOLUS, time,
                null, null, 150, null, null, null, null, null, "Bolus", false, null);
        for (float density : new float[] {1f, 2f, 3f}) {
            float plotTop = 32 * density;
            // Default 256 dp chart: plot bottom at 222 dp, minus the 24 dp basal band.
            float markerBottom = 198 * density;
            float inset = 18 * density;
            float size = ChartMarkerHitAreas.minimumSizePx(density);
            // Bolus on the top lane, carbs on the bottom lane just above the basal band.
            float bolusY = plotTop + inset;
            float carbsY = markerBottom - inset;

            assertTrue("carbs icon must stay above the basal band", carbsY + 12 * density <= markerBottom);
            assertTrue("carbs lane must be below the bolus lane", carbsY > bolusY);
            ChartMarkerHitAreas.Target carbsTarget = new ChartMarkerHitAreas.Target(carbs, 100, carbsY, size, size);
            ChartMarkerHitAreas.Target bolusTarget = new ChartMarkerHitAreas.Target(bolus, 100, bolusY, size, size);
            assertFalse(carbsTarget.overlaps(bolusTarget));
            assertEquals(List.of(carbs),
                    ChartMarkerHitAreas.overlappingEntries(List.of(carbsTarget, bolusTarget), carbsTarget));
            assertEquals(List.of(bolus),
                    ChartMarkerHitAreas.overlappingEntries(List.of(carbsTarget, bolusTarget), bolusTarget));
        }
    }

    private static ChartMarkerHitAreas.Target target(Entry entry, float x) {
        return new ChartMarkerHitAreas.Target(entry, x, 50, 48, 48);
    }

    private static Entry entry(String id, Instant time) {
        return new Entry(id, EntryOrigin.TREATMENTS, EntryType.CARBS, time,
                null, 15, null, null, null, null, null, null, "Carbs", false, null);
    }
}
