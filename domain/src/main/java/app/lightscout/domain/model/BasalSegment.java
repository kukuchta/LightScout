package app.lightscout.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One computed basal segment for the chart band (03 §6.1, ADR-031).
 *
 * @param start            segment start (UTC)
 * @param duration         segment length
 * @param rateMilliPerHour rate in milli-units per hour
 * @param fromProfile      {@code true} when taken from the profile baseline, {@code false} for a temp basal
 */
public record BasalSegment(Instant start, Duration duration, int rateMilliPerHour, boolean fromProfile) {
}
