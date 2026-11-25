package app.lightscout.feature.home;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Local calendar ticks for a viewport; stepping by local calendar time preserves DST day lengths. */
public final class ChartTimeAxis {

    private static final int[] HOUR_CADENCES = {1, 2, 3, 4, 6, 8, 12, 24};

    private ChartTimeAxis() {
    }

    public static List<Instant> dayBoundaries(ChartViewport viewport, ZoneId zone) {
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(zone, "zone");
        Instant from = viewport.end().minus(viewport.span());
        ArrayList<Instant> result = new ArrayList<>();
        ZonedDateTime day = from.atZone(zone).toLocalDate().atStartOfDay(zone);
        while (day.toInstant().isBefore(viewport.end())) {
            Instant boundary = day.toInstant();
            if (!boundary.isBefore(from)) {
                result.add(boundary);
            }
            day = day.plusDays(1);
        }
        return List.copyOf(result);
    }

    public static List<Instant> hourBoundaries(ChartViewport viewport, ZoneId zone) {
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(zone, "zone");
        Instant from = viewport.end().minus(viewport.span());
        ArrayList<Instant> result = new ArrayList<>();
        ZonedDateTime hour = from.atZone(zone).toLocalDate().atStartOfDay(zone);
        if (hour.toInstant().isBefore(from)) {
            hour = hour.plusHours(1);
        }
        while (hour.toInstant().isBefore(viewport.end())) {
            if (!hour.toInstant().isBefore(from)) {
                result.add(hour.toInstant());
            }
            hour = hour.plusHours(1);
        }
        return List.copyOf(result);
    }

    public static List<HourTick> hourTicks(ChartViewport viewport, ZoneId zone, float plotLeft, float plotWidth,
                                           float minimumSpacing) {
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(zone, "zone");
        if (plotWidth < 0 || minimumSpacing < 0) {
            throw new IllegalArgumentException("plot width and minimum spacing must not be negative");
        }
        Instant start = viewport.end().minus(viewport.span());
        int cadenceHours = hourCadence(viewport.span(), plotWidth, minimumSpacing);
        double spanNanos = viewport.span().toNanos();
        ArrayList<HourTick> result = new ArrayList<>();
        for (Instant instant : hourBoundaries(viewport, zone)) {
            // Anchor sparse ticks to local wall time, not the viewport edge; hourBoundaries retains DST offsets.
            int localHour = instant.atZone(zone).getHour();
            if (localHour % cadenceHours != 0) {
                continue;
            }
            float x = plotLeft + (float) (Duration.between(start, instant).toNanos() / spanNanos * plotWidth);
            result.add(new HourTick(instant, x, hourLabel(instant, zone)));
        }
        return List.copyOf(result);
    }

    public static List<HourTick> nonOverlappingHourTicks(ChartViewport viewport, ZoneId zone, float plotLeft,
                                                          float plotWidth, float minimumSpacing) {
        List<HourTick> candidates = hourTicks(viewport, zone, plotLeft, plotWidth, minimumSpacing);
        ArrayList<HourTick> result = new ArrayList<>();
        float previousX = Float.NEGATIVE_INFINITY;
        for (HourTick tick : candidates) {
            if (tick.x() - previousX >= minimumSpacing) {
                result.add(tick);
                previousX = tick.x();
            }
        }
        return List.copyOf(result);
    }

    private static int hourCadence(Duration span, float plotWidth, float minimumSpacing) {
        if (plotWidth == 0 || minimumSpacing == 0) {
            return 1;
        }
        double hours = span.toNanos() / (double) Duration.ofHours(1).toNanos();
        double requiredCadence = Math.ceil(minimumSpacing * hours / plotWidth);
        for (int cadence : HOUR_CADENCES) {
            if (cadence >= requiredCadence) {
                return cadence;
            }
        }
        return HOUR_CADENCES[HOUR_CADENCES.length - 1];
    }

    public static String dayLabel(Instant instant, ZoneId zone, Locale locale, String pattern) {
        Objects.requireNonNull(instant, "instant");
        return DateTimeFormatter.ofPattern(Objects.requireNonNull(pattern, "pattern"),
                Objects.requireNonNull(locale, "locale")).format(instant.atZone(Objects.requireNonNull(zone, "zone")));
    }

    public static String hourLabel(Instant instant, ZoneId zone) {
        int hour = Objects.requireNonNull(instant, "instant").atZone(Objects.requireNonNull(zone, "zone")).getHour();
        return String.format(Locale.ROOT, "%02d", hour);
    }

    public record HourTick(Instant instant, float x, String label) {
    }
}
