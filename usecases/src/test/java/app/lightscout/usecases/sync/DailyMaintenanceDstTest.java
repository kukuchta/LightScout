package app.lightscout.usecases.sync;

import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * BUG-03 / Q-60: WF-12 daily maintenance „first tick after 03:00 local” (06 §4, T-2) in Europe/Warsaw on the
 * spring-forward (2026-03-29, 02:00 → 03:00) and fall-back (2026-10-25, 03:00 → 02:00) days.
 */
public class DailyMaintenanceDstTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    /** Simulates ticks every {@code step} over [from, to) and returns the run instants. */
    private static List<Instant> runs(Instant from, Instant to, Duration step, Instant initialLast) {
        List<Instant> runs = new ArrayList<>();
        Instant last = initialLast;
        for (Instant t = from; t.isBefore(to); t = t.plus(step)) {
            if (DailyMaintenance.isDue(t, WARSAW, last)) {
                runs.add(t);
                last = t;
            }
        }
        return runs;
    }

    /** One run per local day, each at the first tick ≥ 03:00 local of that day. */
    private static void assertOnePerDayAtFirstTickAfterThree(List<Instant> runs, Instant from, Instant to,
                                                            Duration step) {
        Map<LocalDate, Instant> byDay = new LinkedHashMap<>();
        for (Instant r : runs) {
            LocalDate d = r.atZone(WARSAW).toLocalDate();
            assertFalse("second run on " + d, byDay.containsKey(d));
            byDay.put(d, r);
        }
        for (Map.Entry<LocalDate, Instant> e : byDay.entrySet()) {
            ZonedDateTime local = e.getValue().atZone(WARSAW);
            assertFalse("run before 03:00 local: " + local, local.toLocalTime().isBefore(LocalTime.of(3, 0)));
            Instant previousTick = e.getValue().minus(step);
            if (!previousTick.isBefore(from)) {
                ZonedDateTime prev = previousTick.atZone(WARSAW);
                assertTrue("not the first tick ≥ 03:00: " + local,
                        prev.toLocalDate().isBefore(e.getKey()) || prev.toLocalTime().isBefore(LocalTime.of(3, 0)));
            }
        }
        // every local day whose 03:00 lies inside the simulated range has a run
        for (LocalDate d = from.atZone(WARSAW).toLocalDate(); !d.isAfter(to.atZone(WARSAW).toLocalDate());
             d = d.plusDays(1)) {
            Instant three = d.atTime(3, 0).atZone(WARSAW).toInstant();
            if (!three.isBefore(from) && three.plus(step).isBefore(to)) {
                assertTrue("no run on " + d, byDay.containsKey(d));
            }
        }
    }

    @Test
    public void springForward_dayBeforeTransitionDayAfter_onePerDayAtFirstTickAfterThree() {
        Instant from = ZonedDateTime.of(2026, 3, 27, 12, 0, 0, 0, WARSAW).toInstant();
        Instant to = ZonedDateTime.of(2026, 3, 31, 12, 0, 0, 0, WARSAW).toInstant();
        for (Duration step : List.of(Duration.ofMinutes(1), Duration.ofMinutes(7), Duration.ofMinutes(60))) {
            List<Instant> runs = runs(from, to, step, from.minus(Duration.ofHours(1)));
            assertEquals("runs with step " + step, 4, runs.size());   // 28, 29, 30, 31 March
            assertOnePerDayAtFirstTickAfterThree(runs, from, to, step);
        }
        // transition day: 03:00 CEST = 01:00Z
        List<Instant> runs = runs(from, to, Duration.ofMinutes(1), from.minus(Duration.ofHours(1)));
        assertTrue(runs.contains(Instant.parse("2026-03-29T01:00:00Z")));
    }

    @Test
    public void springForward_ticksStraddlingTheMissingHour() {
        Instant lastYesterday = ZonedDateTime.of(2026, 3, 28, 3, 5, 0, 0, WARSAW).toInstant();
        // 01:59 CET (00:59Z) → not due; the next instant is 03:00 CEST (01:00Z) → due; 03:01 → no second run
        assertFalse(DailyMaintenance.isDue(Instant.parse("2026-03-29T00:59:00Z"), WARSAW, lastYesterday));
        assertTrue(DailyMaintenance.isDue(Instant.parse("2026-03-29T01:00:00Z"), WARSAW, lastYesterday));
        assertFalse(DailyMaintenance.isDue(Instant.parse("2026-03-29T01:01:00Z"), WARSAW,
                Instant.parse("2026-03-29T01:00:00Z")));
        // a tick that jumps over the gap (01:30 CET → 03:30 CEST) still runs once
        assertTrue(DailyMaintenance.isDue(Instant.parse("2026-03-29T01:30:00Z"), WARSAW, lastYesterday));
    }

    @Test
    public void fallBack_dayBeforeTransitionDayAfter_onePerDayAtFirstTickAfterThree() {
        Instant from = ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, WARSAW).toInstant();
        Instant to = ZonedDateTime.of(2026, 10, 27, 12, 0, 0, 0, WARSAW).toInstant();
        for (Duration step : List.of(Duration.ofMinutes(1), Duration.ofMinutes(7), Duration.ofMinutes(60))) {
            List<Instant> runs = runs(from, to, step, from.minus(Duration.ofHours(1)));
            assertEquals("runs with step " + step, 4, runs.size());   // 24, 25, 26, 27 October
            assertOnePerDayAtFirstTickAfterThree(runs, from, to, step);
        }
        // transition day: 03:00 CET = 02:00Z (after the repeated 02:xx hour)
        List<Instant> runs = runs(from, to, Duration.ofMinutes(1), from.minus(Duration.ofHours(1)));
        assertTrue(runs.contains(Instant.parse("2026-10-25T02:00:00Z")));
    }

    @Test
    public void fallBack_ticksInTheRepeatedHour_noRunUntilThreeLocal() {
        Instant lastYesterday = ZonedDateTime.of(2026, 10, 24, 3, 5, 0, 0, WARSAW).toInstant();
        // 02:30 CEST (00:30Z), 02:59 CEST (00:59Z), 02:00 CET (01:00Z), 02:59 CET (01:59Z): all before 03:00 local
        for (String z : List.of("2026-10-25T00:30:00Z", "2026-10-25T00:59:00Z", "2026-10-25T01:00:00Z",
                "2026-10-25T01:59:00Z")) {
            assertFalse(z, DailyMaintenance.isDue(Instant.parse(z), WARSAW, lastYesterday));
        }
        assertTrue(DailyMaintenance.isDue(Instant.parse("2026-10-25T02:00:00Z"), WARSAW, lastYesterday));
        assertFalse(DailyMaintenance.isDue(Instant.parse("2026-10-25T02:30:00Z"), WARSAW,
                Instant.parse("2026-10-25T02:00:00Z")));
    }

    @Test
    public void cycle_runsDailyMaintenanceOnceOnFallBackDay() {
        Harness h = new Harness();
        h.clock.zone = WARSAW;
        Instant start = Instant.parse("2026-10-25T00:00:00Z");   // 02:00 CEST
        h.clock.now = start;
        h.monitorState.value = withDaily(Harness.steadyState(start), Instant.parse("2026-10-24T01:10:00Z"));
        List<Instant> runsSeen = new ArrayList<>();
        Instant lastDaily = h.state().lastDailyMaintenance();
        for (int i = 0; i < 60; i++) {   // 5 h of ticks every 5 min across both 02:xx hours
            h.tick();
            if (!h.state().lastDailyMaintenance().equals(lastDaily)) {
                lastDaily = h.state().lastDailyMaintenance();
                runsSeen.add(lastDaily);
            }
            h.clock.advanceMinutes(5);
        }
        assertEquals(List.of(Instant.parse("2026-10-25T02:00:00Z")), runsSeen);
    }

    private static app.lightscout.domain.monitor.MonitorState withDaily(app.lightscout.domain.monitor.MonitorState s,
                                                                       Instant lastDaily) {
        return new app.lightscout.domain.monitor.MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(),
                s.lastCheckCompleted(), s.lastOutcome(), s.connectionProblem(), s.lastReadingTime(),
                s.arrivalLagMedianSec(), s.recentLagSec(), lastDaily, s.cursors());
    }
}
