package app.lightscout.domain.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A statistics period (CP-00A R-7).
 *
 * @param kind  the period kind
 * @param start inclusive start (UTC)
 * @param end   exclusive end (UTC)
 */
public record Period(PeriodKind kind, Instant start, Instant end) {

    /** One expected CGM reading per 5 minutes. */
    private static final Duration READING_INTERVAL = Duration.ofMinutes(5);

    /**
     * Creates a period.
     *
     * @throws NullPointerException     if a component is null
     * @throws IllegalArgumentException if {@code end} is before {@code start}
     */
    public Period {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end before start");
        }
    }

    /**
     * Returns the number of expected readings: the period length divided by 5 minutes
     * (288 per 24 h, 276 on a 23 h day, 300 on a 25 h day).
     *
     * @return expected readings
     */
    public long expectedReadings() {
        return Duration.between(start, end).dividedBy(READING_INTERVAL);
    }
}
