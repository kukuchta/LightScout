package app.lightscout.feature.home;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A chart window ending at {@code end}, with the requested horizontal time span. */
public record ChartViewport(Instant end, Duration span) {

    public ChartViewport {
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(span, "span");
    }
}
