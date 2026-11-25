package app.lightscout.widget;

import java.time.Duration;
import java.util.Objects;

import app.lightscout.domain.model.Reading;

/**
 * Immutable S-12 state mapped from one status snapshot.
 */
public record WidgetPresentation(State state, Reading latest, RangeLabel rangeLabel,
                                 Duration age, boolean stale, int lowMgdl, int highMgdl,
                                 Integer deltaMgdl) {

    /** Without a delta (empty states and tests). */
    public WidgetPresentation(State state, Reading latest, RangeLabel rangeLabel, Duration age, boolean stale,
                              int lowMgdl, int highMgdl) {
        this(state, latest, rangeLabel, age, stale, lowMgdl, highMgdl, null);
    }

    public enum State {
        FIRST_USE,
        NO_READINGS,
        CONTENT
    }

    public enum RangeLabel {
        NONE,
        URGENT_LOW,
        LOW,
        HIGH
    }

    public WidgetPresentation {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rangeLabel, "rangeLabel");
        if (state == State.CONTENT) {
            Objects.requireNonNull(latest, "latest");
            Objects.requireNonNull(age, "age");
        }
    }
}
