package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.usecases.SyncResult;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * CP-08 §9 „JVM — schedule calculator”, „JVM — F12” and the :monitor half of „JVM — daily trigger”.
 */
public class TickScheduleTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(20);
    private final TickSchedule schedule = new TickSchedule();
    private final Instant reading = Fakes.T0;   // lastReading.time

    @Test
    public void expectedTime_isReadingPlus5Min_firstWindowTickAtEPlusLagPlus5s() {
        // E = 12:05:00; lag 60 → first window tick 12:06:05 (D-152)
        TickPlan p = schedule.schedule(reading.plusSeconds(10), reading, null, 60, THRESHOLD);
        assertEquals(TickPhase.EXPECTED_READING, p.phase());
        assertEquals(reading.plus(Duration.ofMinutes(5)).plusSeconds(65), p.at());
    }

    @Test
    public void lag_isClampedTo15And180_initial60() {
        Instant e = reading.plus(Duration.ofMinutes(5));
        assertEquals(e.plusSeconds(15 + 5), schedule.schedule(reading, reading, null, 3, THRESHOLD).at());
        assertEquals(e.plusSeconds(180 + 5), schedule.schedule(reading, reading, null, 999, THRESHOLD).at());
        assertEquals(e.plusSeconds(60 + 5), schedule.schedule(reading, reading, null, 0, THRESHOLD).at());
        assertEquals(60, TickSchedule.clampLag(0));
        assertEquals(15, TickSchedule.clampLag(15));
        assertEquals(180, TickSchedule.clampLag(180));
    }

    @Test
    public void window_every60s_untilEPlus5Min() {
        Instant windowStart = reading.plus(Duration.ofMinutes(5)).plusSeconds(65);
        TickPlan p = schedule.schedule(windowStart, reading, null, 60, THRESHOLD);
        assertEquals(TickPhase.ARRIVAL_WINDOW, p.phase());
        assertEquals(windowStart.plusSeconds(60), p.at());
        Instant lastInWindow = reading.plus(Duration.ofMinutes(10)).minusSeconds(1);
        assertEquals(TickPhase.ARRIVAL_WINDOW, schedule.schedule(lastInWindow, reading, null, 60, THRESHOLD).phase());
    }

    @Test
    public void newReading_restartsTheCycle() {
        Instant now = reading.plus(Duration.ofMinutes(7));
        Instant newReading = reading.plus(Duration.ofMinutes(6));
        TickPlan p = schedule.schedule(now, newReading, null, 60, THRESHOLD);
        assertEquals(TickPhase.EXPECTED_READING, p.phase());
        assertEquals(newReading.plus(Duration.ofMinutes(5)).plusSeconds(65), p.at());
    }

    @Test
    public void overdue_every2Min_fromEPlus5Min() {
        Instant now = reading.plus(Duration.ofMinutes(10));
        TickPlan p = schedule.schedule(now, reading, null, 60, THRESHOLD);
        assertEquals(TickPhase.OVERDUE, p.phase());
        assertEquals(now.plus(Duration.ofMinutes(2)), p.at());
    }

    @Test
    public void overdue_neverSkipsTheThresholdInstant() {
        Instant now = reading.plus(Duration.ofMinutes(19));
        TickPlan p = schedule.schedule(now, reading, null, 60, THRESHOLD);
        assertEquals(reading.plus(THRESHOLD), p.at());
    }

    @Test
    public void dataLoss_every5Min_afterThreshold() {
        Instant now = reading.plus(THRESHOLD);
        TickPlan p = schedule.schedule(now, reading, null, 60, THRESHOLD);
        assertEquals(TickPhase.DATA_LOSS, p.phase());
        assertEquals(now.plus(Duration.ofMinutes(5)), p.at());
    }

    @Test
    public void m13_anchorIgnoresReadingsLaterThanNowPlus10Min() {
        Instant now = reading;
        TickPlan future = schedule.schedule(now, now.plus(Duration.ofMinutes(11)), null, 60, THRESHOLD);
        assertEquals(TickPhase.NO_READING, future.phase());
        TickPlan atLimit = schedule.schedule(now, now.plus(Duration.ofMinutes(10)), null, 60, THRESHOLD);
        assertEquals(TickPhase.EXPECTED_READING, atLimit.phase());
    }

    @Test
    public void q58_noReading_noAnchor_every2Min() {
        TickPlan p = schedule.schedule(reading, null, null, 60, THRESHOLD);
        assertEquals(TickPhase.NO_READING, p.phase());
        assertEquals(reading.plus(Duration.ofMinutes(2)), p.at());
    }

    @Test
    public void q58_noReading_beforeFirstUseThreshold_every2Min() {
        Instant since = reading;
        TickPlan p = schedule.schedule(since.plus(Duration.ofMinutes(5)), null, since, 60, THRESHOLD);
        assertEquals(TickPhase.NO_READING, p.phase());
        assertEquals(since.plus(Duration.ofMinutes(7)), p.at());
    }

    @Test
    public void q58_noReading_landsOnFirstUseThreshold() {
        Instant since = reading;
        TickPlan p = schedule.schedule(since.plus(Duration.ofMinutes(19)), null, since, 60, THRESHOLD);
        assertEquals(since.plus(THRESHOLD), p.at());
    }

    @Test
    public void q58_noReading_atAndAfterFirstUseThreshold_every5Min() {
        Instant since = reading;
        Instant atBoundary = since.plus(THRESHOLD);
        TickPlan p = schedule.schedule(atBoundary, null, since, 60, THRESHOLD);
        assertEquals(TickPhase.DATA_LOSS, p.phase());
        assertEquals(atBoundary.plus(Duration.ofMinutes(5)), p.at());
        Instant justBefore = atBoundary.minusMillis(1);
        assertEquals(TickPhase.NO_READING, schedule.schedule(justBefore, null, since, 60, THRESHOLD).phase());
    }

    @Test
    public void q58_f12_stillPullsFirstUseTickForward() {
        Instant since = reading;
        Instant now = since.plus(Duration.ofMinutes(30));          // data-loss phase → +5 min
        Instant nextEval = now.plusSeconds(40);
        TickPlan p = schedule.next(now, Fakes.result(FetchOutcome.OK, null, nextEval, false), since);
        assertEquals(TickPhase.EVALUATION, p.phase());
        assertEquals(nextEval, p.at());
    }

    @Test
    public void f12_nextEvaluationEarlierThanSchedule_wins() {
        Instant now = reading.plus(THRESHOLD);   // data-loss cadence → now + 5 min
        Instant snoozedUntil = now.plus(Duration.ofMinutes(1));
        SyncResult r = Fakes.result(FetchOutcome.OK, reading, snoozedUntil, false);
        TickPlan p = schedule.next(now, r, null);
        assertEquals(TickPhase.EVALUATION, p.phase());
        assertEquals(snoozedUntil, p.at());
    }

    @Test
    public void f12_nextEvaluationLaterThanSchedule_scheduleWins() {
        Instant now = reading.plusSeconds(10);
        SyncResult r = Fakes.result(FetchOutcome.OK, reading, now.plus(Duration.ofHours(1)), false);
        assertEquals(TickPhase.EXPECTED_READING, schedule.next(now, r, null).phase());
    }

    @Test
    public void f12_nonFutureNextEvaluation_isIgnored_noBusyLoop() {
        Instant now = reading.plusSeconds(10);
        SyncResult r = Fakes.result(FetchOutcome.OK, reading, now, false);
        assertFalse(schedule.next(now, r, null).at().equals(now));
    }

    @Test
    public void f12_aTickLandsWithin10sAfterThreshold_overWholeOutage() {
        // Simulate an outage: ticks follow the plan; the evaluator reports lastReading + threshold (F12).
        Instant threshold = reading.plus(THRESHOLD);
        Instant now = reading.plusSeconds(30);
        boolean landed = false;
        for (int i = 0; i < 100 && now.isBefore(threshold.plus(Duration.ofMinutes(1))); i++) {
            Instant nextEval = now.isBefore(threshold) ? threshold : null;
            TickPlan p = schedule.next(now, Fakes.result(FetchOutcome.NO_INTERNET, reading, nextEval, false), null);
            now = p.at();
            if (!now.isBefore(threshold) && !now.isAfter(threshold.plusSeconds(10))) {
                landed = true;
            }
        }
        assertTrue(landed);
    }

    @Test
    public void afterFailure_retriesIn2Min() {
        TickPlan p = schedule.afterFailure(reading);
        assertEquals(TickPhase.RETRY, p.phase());
        assertEquals(reading.plus(Duration.ofMinutes(2)), p.at());
    }

    @Test
    public void dailyTrigger_scheduleNeverLeavesMoreThan5MinGap_soATickFollows0300_incDstDays() {
        // WF-12 runs inside the first tick after 03:00 (CP-02 DailyMaintenance). :monitor's part: there always is a
        // tick ≤ 5 min after 03:00 local, also on both DST days (Europe/Warsaw 2026-03-29 and 2026-10-25).
        ZoneId zone = ZoneId.of("Europe/Warsaw");
        for (LocalDate day : new LocalDate[] {LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25),
                LocalDate.of(2026, 10, 15)}) {
            Instant boundary = ZonedDateTime.of(day, LocalTime.of(3, 0), zone).toInstant();
            Instant lastReading = boundary.minus(Duration.ofHours(2));   // long outage: data-loss cadence
            Instant now = boundary.minus(Duration.ofHours(1));
            Instant firstAfter = null;
            while (firstAfter == null) {
                Instant next = schedule.next(now, Fakes.result(FetchOutcome.OK, lastReading, null, false), null).at();
                assertTrue(Duration.between(now, next).compareTo(Duration.ofMinutes(5)) <= 0);
                if (!next.isBefore(boundary)) {
                    firstAfter = next;
                }
                now = next;
            }
            assertTrue(Duration.between(boundary, firstAfter).compareTo(Duration.ofMinutes(5)) <= 0);
        }
    }

    /** BUG-18 N1: an evaluation instant that fell due during the tick (after its start) is armed at once. */
    @Test
    public void n1_nextEvaluationDuringTheTick_armedAtNow() {
        Instant started = reading.plus(THRESHOLD);
        Instant now = started.plusSeconds(40);                       // maintenance / backfill took 40 s
        SyncResult r = Fakes.result(FetchOutcome.OK, reading, started.plusSeconds(10), false);   // snooze end
        TickPlan p = schedule.next(started, now, r, null);
        assertEquals(TickPhase.EVALUATION, p.phase());
        assertEquals(now, p.at());
    }

    /** BUG-18 N1: an evaluation instant at or before the tick start was handled by it (A-3, no loop). */
    @Test
    public void n1_nextEvaluationAtTickStart_dropped() {
        Instant started = reading.plusSeconds(10);
        Instant now = started.plusSeconds(40);
        SyncResult r = Fakes.result(FetchOutcome.OK, reading, started, false);
        assertEquals(TickPhase.EXPECTED_READING, schedule.next(started, now, r, null).phase());
    }

    /** BUG-18 N1: the follow-up tick does not loop — its own nextEvaluation lies after its start. */
    @Test
    public void n1_followUpTick_noLoop() {
        Instant started = reading.plus(THRESHOLD);
        Instant now = started.plusSeconds(40);
        Instant due = started.plusSeconds(10);
        TickPlan p = schedule.next(started, now, Fakes.result(FetchOutcome.OK, reading, due, false), null);
        // the follow-up tick starts at p.at() and evaluates; the evaluator reports a later instant or none
        Instant started2 = p.at();
        TickPlan p2 = schedule.next(started2, started2.plusSeconds(1),
                Fakes.result(FetchOutcome.OK, reading, due, false), null);
        assertTrue(p2.at().isAfter(started2.plusSeconds(1)));
    }
}
