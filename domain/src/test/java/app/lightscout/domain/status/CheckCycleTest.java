package app.lightscout.domain.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.lightscout.domain.monitor.CheckSchedule;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.CheckCycle.Kind;

import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

/** D-151: the cycle between two checks and how it reads; the schedule itself is covered by TickScheduleTest. */
public class CheckCycleTest {

    private static final Instant T = Instant.parse("2026-11-17T10:00:00Z");   // reading time
    private static final Duration THRESHOLD = Duration.ofMinutes(20);
    private static final int LAG = 60;
    private static final Instant WINDOW_START = T.plusSeconds(5 * 60 + 60 + 5);   // T + 6:05 (E + lag + 5 s)

    private static CheckCycle cycle(Instant now, Instant lastCheck, FetchOutcome outcome, FetchOutcome problem) {
        return CheckCycle.of(now, lastCheck, outcome, problem, T, T.minusSeconds(3600), LAG, LAG, THRESHOLD);
    }

    @Test
    public void noCompletedCheckHasNoCycle() {
        assertNull(CheckCycle.of(T, null, FetchOutcome.OK, null, T, null, LAG, null, THRESHOLD));
    }

    @Test
    public void slowCycleRunsFromTheCheckThatFoundTheReadingToTheFirstWindowTick() {
        Instant found = T.plusSeconds(60);   // the check that stored the reading, 1:00 after its timestamp
        CheckCycle c = cycle(found.plusSeconds(10), found, FetchOutcome.OK, null);
        assertEquals(Kind.NORMAL, c.kind());
        assertEquals(found, c.start());
        assertEquals(WINDOW_START, c.next());
        assertEquals(Duration.ofSeconds(305), Duration.between(c.start(), c.next()));
    }

    @Test
    public void anExtraCheckInTheSlowCycleDoesNotRestartTheBar() {
        // Opening Home refreshes: a check ends two minutes after the reading was found, the bar must keep its start
        Instant arrival = T.plusSeconds(LAG);   // when the server had the reading
        Instant extra = arrival.plusSeconds(120);
        CheckCycle c = cycle(extra, extra, FetchOutcome.OK, null);
        assertEquals(Kind.NORMAL, c.kind());
        assertEquals(arrival, c.start());
        assertEquals(WINDOW_START, c.next());
        // a missing lag sample falls back to the schedule's lag
        CheckCycle fallback = CheckCycle.of(extra, extra, FetchOutcome.OK, null, T, null, LAG, null, THRESHOLD);
        assertEquals(arrival, fallback.start());
        // a failed extra check is a cycle between checks (orange), it does not use the reading arrival
        assertEquals(extra, cycle(extra, extra, FetchOutcome.NO_INTERNET, null).start());
    }

    @Test
    public void firstMissInTheWindowIsAShortCycleInTheAccentColour() {
        Instant check = WINDOW_START.plusSeconds(3);
        CheckCycle c = cycle(check.plusSeconds(5), check, FetchOutcome.OK, null);
        assertEquals(Kind.WAITING, c.kind());
        assertEquals(check.plusSeconds(60), c.next());
    }

    @Test
    public void secondMissInARowIsOrange() {
        Instant second = WINDOW_START.plusSeconds(60 + 4);
        CheckCycle c = cycle(second, second, FetchOutcome.OK, null);
        assertEquals(Kind.MISSED, c.kind());
        assertEquals(second.plusSeconds(60), c.next());
        Instant third = WINDOW_START.plusSeconds(120 + 6);
        assertEquals(Kind.MISSED, cycle(third, third, FetchOutcome.OK, null).kind());
    }

    @Test
    public void aFailedCheckIsOrangeAtOnceAndASlowOneIsStillCounted() {
        Instant check = WINDOW_START.plusSeconds(2);
        assertEquals(Kind.MISSED, cycle(check, check, FetchOutcome.NO_INTERNET, null).kind());
        assertEquals(Kind.MISSED, cycle(check, check, FetchOutcome.NS_NOT_RESPONDING, null).kind());
        assertEquals(Kind.MISSED, cycle(check, check, FetchOutcome.OK, FetchOutcome.NOT_NIGHTSCOUT).kind());
        // a failure before the first window tick is orange too
        Instant early = T.plusSeconds(120);
        assertEquals(Kind.MISSED, cycle(early, early, FetchOutcome.NO_INTERNET, null).kind());
    }

    @Test
    public void overdueChecksAreOrangeAtTheTwoMinuteCadence() {
        Instant check = T.plus(Duration.ofMinutes(11));
        CheckCycle c = cycle(check, check, FetchOutcome.OK, null);
        assertEquals(Kind.MISSED, c.kind());
        assertEquals(check.plus(CheckSchedule.OVERDUE_CADENCE), c.next());
    }

    @Test
    public void noDataIsGreyAtTheFiveMinuteCadence() {
        Instant check = T.plus(THRESHOLD).plusSeconds(10);
        CheckCycle c = cycle(check.plusSeconds(1), check, FetchOutcome.OK, null);
        assertEquals(Kind.NO_DATA, c.kind());
        assertEquals(check.plus(CheckSchedule.DATA_LOSS_CADENCE), c.next());
        // grey even when the last check failed
        assertEquals(Kind.NO_DATA, cycle(check, check, FetchOutcome.NO_INTERNET, null).kind());
        // the threshold passed between checks: grey as soon as it is crossed, the next check lands on it
        Instant before = T.plus(THRESHOLD).minusSeconds(30);
        CheckCycle landing = cycle(T.plus(THRESHOLD).plusSeconds(1), before, FetchOutcome.OK, null);
        assertEquals(Kind.NO_DATA, landing.kind());
    }

    @Test
    public void nextIsNeverBeforeStart() {
        CheckCycle c = new CheckCycle(T, T.minusSeconds(5), Kind.NORMAL);
        assertEquals(T, c.next());
    }
}
