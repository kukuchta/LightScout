package app.lightscout.usecases;

import app.lightscout.domain.model.BasalSegment;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.Reading;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One chart window delivered by IF-22 {@code loadSegment} (CP-00A R-10, R-26).
 *
 * @param from     inclusive start
 * @param to       exclusive end
 * @param readings readings passed through {@code ReadingRules.displayable}
 * @param entries  marker entries of the window (never {@code TEMP_BASAL})
 * @param basal    basal band segments (R-26; empty when there is no temp basal — D-017)
 */
public record Segment(Instant from, Instant to, List<Reading> readings, List<Entry> entries,
                      List<BasalSegment> basal) {

    /**
     * Creates the segment with unmodifiable copies of the lists.
     *
     * @throws NullPointerException if a component is null
     */
    public Segment {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        readings = List.copyOf(Objects.requireNonNull(readings, "readings"));
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        basal = List.copyOf(Objects.requireNonNull(basal, "basal"));
    }
}
