package app.lightscout.feature.home;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;

/** Platform-independent marker hit-target and overlap grouping rules. */
public final class ChartMarkerHitAreas {

    public static final float MINIMUM_DP = 48f;

    private ChartMarkerHitAreas() {
    }

    public static float minimumSizePx(float density) {
        if (!Float.isFinite(density) || density <= 0) {
            throw new IllegalArgumentException("density must be positive");
        }
        return MINIMUM_DP * density;
    }

    /** Returns every marker whose minimum tap target overlaps the selected marker, ordered by event time. */
    public static List<Entry> overlappingEntries(List<Target> targets, Target selected) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(selected, "selected");
        ArrayList<Entry> result = new ArrayList<>();
        for (Target target : targets) {
            if (target.overlaps(selected)) {
                result.add(target.entry());
            }
        }
        result.sort(Comparator.comparing(Entry::time));
        return List.copyOf(result);
    }

    public record Target(Entry entry, float centerX, float centerY, float widthPx, float heightPx) {
        public Target {
            Objects.requireNonNull(entry, "entry");
            if (widthPx < 0 || heightPx < 0) {
                throw new IllegalArgumentException("target dimensions must not be negative");
            }
        }

        public boolean contains(float x, float y) {
            return x >= centerX - widthPx / 2 && x <= centerX + widthPx / 2
                    && y >= centerY - heightPx / 2 && y <= centerY + heightPx / 2;
        }

        public boolean overlaps(Target other) {
            return Math.abs(centerX - other.centerX) * 2 <= widthPx + other.widthPx
                    && Math.abs(centerY - other.centerY) * 2 <= heightPx + other.heightPx;
        }
    }
}
