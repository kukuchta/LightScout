package app.lightscout.feature.home;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Pure time-window rules for the CP-11 glucose chart. */
public final class ChartViewportRules {

    public static final Duration DEFAULT_SPAN = Duration.ofHours(6);
    public static final Duration MIN_SPAN = Duration.ofHours(1);
    public static final Duration MAX_SPAN = Duration.ofHours(24);
    public static final Duration MAX_HISTORY = Duration.ofDays(7);
    private static final Duration FOLLOW_NOW_TOLERANCE = Duration.ofMinutes(1);

    private ChartViewportRules() {
    }

    public static ChartViewport defaultViewport(Instant now) {
        return new ChartViewport(Objects.requireNonNull(now, "now"), DEFAULT_SPAN);
    }

    /** Clamps both span and edges to the approved viewport bounds. */
    public static ChartViewport clamp(ChartViewport viewport, Instant now) {
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(now, "now");
        Duration span = clampSpan(viewport.span());
        Instant earliestEnd = now.minus(MAX_HISTORY).plus(span);
        Instant end = viewport.end().isAfter(now) ? now : viewport.end();
        if (end.isBefore(earliestEnd)) {
            end = earliestEnd;
        }
        return new ChartViewport(end, span);
    }

    /** Moves the right edge by {@code delta}; a positive delta pans toward older readings. */
    public static ChartViewport pan(ChartViewport viewport, Duration delta, Instant now) {
        Objects.requireNonNull(delta, "delta");
        ChartViewport bounded = clamp(viewport, now);
        return clamp(new ChartViewport(bounded.end().minus(delta), bounded.span()), now);
    }

    /** Zooms around the horizontal anchor fraction, preserving the time under that point. */
    public static ChartViewport zoom(ChartViewport viewport, double scaleFactor, float anchorFraction, Instant now) {
        ChartViewport bounded = clamp(viewport, now);
        if (!Double.isFinite(scaleFactor) || scaleFactor <= 0) {
            return bounded;
        }
        double anchor = Math.max(0d, Math.min(1d, anchorFraction));
        Instant anchoredTime = bounded.end().minusNanos(
                Math.round(bounded.span().toNanos() * (1d - anchor)));
        long nanos = Math.round(bounded.span().toNanos() / scaleFactor);
        Duration span = clampSpan(Duration.ofNanos(nanos));
        Instant end = anchoredTime.plusNanos(Math.round(span.toNanos() * (1d - anchor)));
        return clamp(new ChartViewport(end, span), now);
    }

    public static boolean followsNow(ChartViewport viewport, Instant now) {
        return Duration.between(viewport.end(), now).abs().compareTo(FOLLOW_NOW_TOLERANCE) <= 0
                && viewport.span().equals(DEFAULT_SPAN);
    }

    private static Duration clampSpan(Duration span) {
        if (span.compareTo(MIN_SPAN) < 0) {
            return MIN_SPAN;
        }
        if (span.compareTo(MAX_SPAN) > 0) {
            return MAX_SPAN;
        }
        return span;
    }
}
