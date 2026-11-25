package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class ChartViewportRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    public void defaultAndZoomSpanBounds() {
        ChartViewport initial = ChartViewportRules.defaultViewport(NOW);
        assertEquals(NOW, initial.end());
        assertEquals(Duration.ofHours(6), initial.span());

        ChartViewport tooNarrow = ChartViewportRules.zoom(initial, 20, 0.5f, NOW);
        assertEquals(Duration.ofHours(1), tooNarrow.span());
        ChartViewport tooWide = ChartViewportRules.zoom(initial, 0.01, 0.5f, NOW);
        assertEquals(Duration.ofHours(24), tooWide.span());
    }

    @Test
    public void panAndZoomPreserveBoundsAndAnchor() {
        ChartViewport initial = ChartViewportRules.defaultViewport(NOW);
        ChartViewport zoomed = ChartViewportRules.zoom(initial, 2, 0.5f, NOW);
        assertEquals(Duration.ofHours(3), zoomed.span());
        Instant oldAnchor = initial.end().minus(Duration.ofHours(3));
        Instant newAnchor = zoomed.end().minus(zoomed.span().dividedBy(2));
        assertEquals(oldAnchor, newAnchor);

        ChartViewport farPast = ChartViewportRules.pan(zoomed, Duration.ofDays(14), NOW);
        assertEquals(NOW.minus(Duration.ofDays(7)), farPast.end().minus(farPast.span()));
        ChartViewport future = ChartViewportRules.pan(initial, Duration.ofDays(-1), NOW);
        assertEquals(NOW, future.end());
    }

    @Test
    public void dayAndHourTicksFollowWarsawDstDays() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        ZonedDateTime springStart = LocalDate.of(2026, 3, 29).atStartOfDay(warsaw);
        Instant springEnd = springStart.plusDays(1).toInstant();
        ChartViewport spring = new ChartViewport(springEnd, Duration.between(springStart.toInstant(), springEnd));
        assertEquals(1, ChartTimeAxis.dayBoundaries(spring, warsaw).size());
        assertEquals(23, ChartTimeAxis.hourBoundaries(spring, warsaw).size());

        ZonedDateTime fallStart = LocalDate.of(2026, 10, 25).atStartOfDay(warsaw);
        Instant fallEnd = fallStart.plusDays(1).toInstant();
        ChartViewport fall = new ChartViewport(fallEnd, Duration.between(fallStart.toInstant(), fallEnd));
        assertEquals(1, ChartTimeAxis.dayBoundaries(fall, warsaw).size());
        assertEquals(25, ChartTimeAxis.hourBoundaries(fall, warsaw).size());
    }

    @Test
    public void nowVisibilityRequiresDefaultWindow() {
        ChartViewport initial = ChartViewportRules.defaultViewport(NOW);
        assertTrue(ChartViewportRules.followsNow(initial, NOW));
        assertTrue(!ChartViewportRules.followsNow(new ChartViewport(NOW, Duration.ofHours(12)), NOW));
        assertTrue(!ChartViewportRules.followsNow(new ChartViewport(NOW.minusSeconds(61), initial.span()), NOW));
    }

    @Test
    public void labelsUseTheSpecifiedLocalDateAndHourFormats() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        Instant localMonday = LocalDate.of(2026, 10, 12).atStartOfDay(warsaw).toInstant();
        assertEquals("Mon 12 Oct", ChartTimeAxis.dayLabel(localMonday, warsaw, Locale.ENGLISH, "EEE d MMM"));
        assertEquals("00", ChartTimeAxis.hourLabel(localMonday, warsaw));
        assertEquals("23", ChartTimeAxis.hourLabel(localMonday.minusSeconds(60), warsaw));
    }

    @Test
    public void midnightDateBoundaryDoesNotSuppressNeighboringHourTicks() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        Instant start = LocalDate.of(2026, 10, 25).atTime(23, 0).atZone(warsaw).toInstant();
        Instant end = LocalDate.of(2026, 10, 26).atTime(3, 0).atZone(warsaw).toInstant();
        List<ChartTimeAxis.HourTick> ticks = ChartTimeAxis.hourTicks(
                new ChartViewport(end, Duration.between(start, end)), warsaw, 0, 400, 20);

        assertEquals(List.of("23", "00", "01", "02"),
                ticks.stream().map(ChartTimeAxis.HourTick::label).collect(Collectors.toList()));
    }

    @Test
    public void repeatedFallBackHourKeepsBothOffsetSpecificInstants() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        ZonedDateTime fallStart = LocalDate.of(2026, 10, 25).atStartOfDay(warsaw);
        Instant end = fallStart.plusDays(1).toInstant();
        List<ChartTimeAxis.HourTick> repeated = ChartTimeAxis.hourTicks(
                new ChartViewport(end, Duration.between(fallStart.toInstant(), end)),
                warsaw, 0, 1_250, 20).stream()
                .filter(tick -> "02".equals(tick.label()))
                .collect(Collectors.toList());

        assertEquals(2, repeated.size());
        assertTrue(!repeated.get(0).instant().equals(repeated.get(1).instant()));
        assertTrue(!repeated.get(0).instant().atZone(warsaw).getOffset()
                .equals(repeated.get(1).instant().atZone(warsaw).getOffset()));
        List<ChartTimeAxis.HourTick> displayedRepeated = ChartTimeAxis.nonOverlappingHourTicks(
                new ChartViewport(end, Duration.between(fallStart.toInstant(), end)),
                warsaw, 0, 1_250, 20).stream()
                .filter(tick -> "02".equals(tick.label()))
                .collect(Collectors.toList());
        assertEquals("both offset-specific labels remain visible when they fit",
                2, displayedRepeated.size());
        assertTrue(displayedRepeated.get(1).x() - displayedRepeated.get(0).x() >= 20);
    }

    @Test
    public void narrowFallBackAxisKeepsBothInstantsAndDisplaysOnlyNonOverlappingLabels() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        Instant start = LocalDate.of(2026, 10, 25).atTime(0, 30).atZone(warsaw).toInstant();
        ChartViewport fall = new ChartViewport(start.plus(Duration.ofHours(24)), Duration.ofHours(24));
        List<ChartTimeAxis.HourTick> axisTicks = ChartTimeAxis.hourTicks(fall, warsaw, 0, 400, 24);
        List<ChartTimeAxis.HourTick> repeated = axisTicks.stream()
                .filter(tick -> "02".equals(tick.label()))
                .collect(Collectors.toList());
        List<ChartTimeAxis.HourTick> displayed = ChartTimeAxis.nonOverlappingHourTicks(
                fall, warsaw, 0, 400, 24);

        assertEquals(2, repeated.size());
        assertTrue(!repeated.get(0).instant().atZone(warsaw).getOffset()
                .equals(repeated.get(1).instant().atZone(warsaw).getOffset()));
        assertEquals(400f / 24f, repeated.get(1).x() - repeated.get(0).x(), 0.01f);
        assertTrue("displayed labels must honor their real-position spacing",
                displayed.size() > 1 && displayed.get(1).x() - displayed.get(0).x() >= 24);
        for (int i = 1; i < displayed.size(); i++) {
            assertTrue("displayed labels must not collide",
                    displayed.get(i).x() - displayed.get(i - 1).x() >= 24);
        }
    }

    @Test
    public void collisionFilterUsesActualInstantsInHalfHourDstZone() {
        ZoneId lordHowe = ZoneId.of("Australia/Lord_Howe");
        Instant start = LocalDate.of(2026, 4, 5).atTime(0, 30).atZone(lordHowe).toInstant();
        ChartViewport fall = new ChartViewport(start.plus(Duration.ofHours(24)), Duration.ofHours(24));
        List<ChartTimeAxis.HourTick> axisTicks = ChartTimeAxis.hourTicks(fall, lordHowe, 0, 576, 24);
        List<ChartTimeAxis.HourTick> repeated = axisTicks.stream()
                .filter(tick -> "01".equals(tick.label()))
                .collect(Collectors.toList());
        List<ChartTimeAxis.HourTick> displayed = ChartTimeAxis.nonOverlappingHourTicks(
                fall, lordHowe, 0, 576, 24);
        List<ChartTimeAxis.HourTick> displayedRepeated = displayed.stream()
                .filter(tick -> "01".equals(tick.label()))
                .collect(Collectors.toList());

        assertEquals(2, repeated.size());
        assertEquals(24f, repeated.get(1).x() - repeated.get(0).x(), 0.01f);
        assertEquals(2, displayedRepeated.size());
        for (int i = 1; i < displayed.size(); i++) {
            assertTrue("displayed labels must honor actual spacing through a half-hour offset change",
                    displayed.get(i).x() - displayed.get(i - 1).x() >= 24);
        }
    }

    @Test
    public void fallBackAxisAndVisibleLabelsRemainStableWhilePanning() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        Instant start = LocalDate.of(2026, 10, 25).atTime(0, 30).atZone(warsaw).toInstant();
        ChartViewport first = new ChartViewport(start.plus(Duration.ofHours(24)), Duration.ofHours(24));
        ChartViewport panned = new ChartViewport(first.end().plus(Duration.ofHours(1)), first.span());

        List<ChartTimeAxis.HourTick> firstAxis = ChartTimeAxis.hourTicks(first, warsaw, 0, 400, 24);
        List<ChartTimeAxis.HourTick> pannedAxis = ChartTimeAxis.hourTicks(panned, warsaw, 0, 400, 24);
        List<ChartTimeAxis.HourTick> firstDisplayed = ChartTimeAxis.nonOverlappingHourTicks(
                first, warsaw, 0, 400, 24);
        List<ChartTimeAxis.HourTick> pannedDisplayed = ChartTimeAxis.nonOverlappingHourTicks(
                panned, warsaw, 0, 400, 24);
        Set<Instant> firstInstants = firstAxis.stream().map(ChartTimeAxis.HourTick::instant)
                .collect(Collectors.toSet());
        Set<Instant> pannedInstants = pannedAxis.stream().map(ChartTimeAxis.HourTick::instant)
                .collect(Collectors.toSet());
        Set<Instant> firstVisible = firstDisplayed.stream().map(ChartTimeAxis.HourTick::instant)
                .collect(Collectors.toSet());
        Set<Instant> pannedVisible = pannedDisplayed.stream().map(ChartTimeAxis.HourTick::instant)
                .collect(Collectors.toSet());

        assertTrue(firstInstants.stream().filter(pannedInstants::contains).count() >= 10);
        assertTrue(firstVisible.stream().filter(pannedVisible::contains).count() >= 10);
        assertTrue(firstAxis.stream().allMatch(tick -> tick.instant().atZone(warsaw).getHour() % 2 == 0));
        assertTrue(pannedAxis.stream().allMatch(tick -> tick.instant().atZone(warsaw).getHour() % 2 == 0));
    }

    @Test
    public void sparseHourTicksStayAnchoredWhenViewportPansAcrossOddHour() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        Instant firstStart = LocalDate.of(2026, 10, 26).atTime(1, 0).atZone(warsaw).toInstant();
        ChartViewport first = new ChartViewport(firstStart.plus(Duration.ofHours(24)), Duration.ofHours(24));
        ChartViewport panned = new ChartViewport(first.end().plus(Duration.ofHours(1)), first.span());

        List<ChartTimeAxis.HourTick> firstTicks = ChartTimeAxis.hourTicks(first, warsaw, 0, 400, 24);
        List<ChartTimeAxis.HourTick> pannedTicks = ChartTimeAxis.hourTicks(panned, warsaw, 0, 400, 24);
        Set<Instant> firstInstants = firstTicks.stream().map(ChartTimeAxis.HourTick::instant)
                .collect(Collectors.toSet());
        Set<Instant> pannedInstants = pannedTicks.stream().map(ChartTimeAxis.HourTick::instant)
                .collect(Collectors.toSet());

        assertTrue("the sparse grid is anchored to even local hours",
                firstTicks.stream().allMatch(tick -> tick.instant().atZone(warsaw).getHour() % 2 == 0));
        assertTrue("panning must retain the same local-hour grid",
                firstInstants.stream().filter(pannedInstants::contains).count() >= 10);
    }

}
