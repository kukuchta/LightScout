package app.lightscout.usecases.sync;

import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.SyncResult;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static app.lightscout.usecases.fakes.Fakes.mbg;
import static app.lightscout.usecases.fakes.Fakes.page;
import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** SY-1, M-13, M-14, SY-2 (incl. paging), SY-5, SY-6, R-21 cursor advance (CP-02 §9). */
public class SyncCycleChangeDetectionTest {

    @Test
    public void sy1_unchangedLastModified_noFetchCallAtAll() {
        Harness h = new Harness();
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.OK, r.outcome());
        assertEquals(List.of("lastModified"), h.gateway.calls);
    }

    @Test
    public void sy1_onlyTreatmentsMoved_onlyTreatmentsFetched() {
        Harness h = new Harness();
        h.lastModified(NsCollection.TREATMENTS, Harness.LM_TREATMENTS + 1);
        h.tick();
        assertTrue(h.gateway.fetchCalls().stream().allMatch(c -> c.equals("entriesWindow")));
        assertFalse(h.gateway.fetchCalls().isEmpty());
        assertEquals(Long.valueOf(Harness.LM_TREATMENTS + 1), h.cursor(NsCollection.TREATMENTS).lastModifiedSeen());
    }

    @Test
    public void m14_futureLastModified_fetchedEveryTick_thenGatedAgain() {
        Harness h = new Harness();
        long future = NOW.plus(Duration.ofMinutes(11)).toEpochMilli();
        h.lastModified(NsCollection.TREATMENTS, future);
        h.tick();
        long first = h.gateway.count("entriesWindow");
        assertTrue(first > 0);
        h.tick();   // same instant, unchanged value, still > now + 10 min → fetched again
        assertEquals(2 * first, h.gateway.count("entriesWindow"));
        // value = now + 9 min and unchanged since the last fetch → gated
        h.clock.now = Instant.ofEpochMilli(future).minus(Duration.ofMinutes(9));
        h.tick();
        assertEquals(2 * first, h.gateway.count("entriesWindow"));
    }

    @Test
    public void sy2_m13_cursorIsMaxReadingTimeNotFuture_fetchFromCursorMinus10Min() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        Instant fresh = NOW.minusSeconds(30);
        Instant future = NOW.plus(Duration.ofMinutes(30));
        h.gateway.readingsSince = ms -> page(true, List.of(reading("a", fresh, 120), reading("f", future, 150)));
        SyncResult r = h.tick();
        long expectedFrom = NOW.minusSeconds(180).minus(Duration.ofMinutes(10)).toEpochMilli();
        assertEquals(List.of(expectedFrom), h.gateway.readingsSinceCalls);
        assertEquals(fresh, h.state().lastReadingTime());   // the reading at now + 30 min does not move the cursor
        assertEquals(fresh, r.lastReadingTime());
        assertTrue(r.newReading());
        assertTrue(h.readings.rows.containsKey("f"));         // stored, but excluded from latest / cursor

        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 2);
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(fresh.minus(Duration.ofMinutes(10)).toEpochMilli(),
                (long) h.gateway.readingsSinceCalls.get(1));
    }

    @Test
    public void sy2_incompletePage_nextCallContinues_allItemsUpserted() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        Instant t1 = NOW.minus(Duration.ofMinutes(9));
        Instant t2 = NOW.minus(Duration.ofMinutes(4));
        Instant t3 = NOW.minusSeconds(20);
        h.gateway.readingsSince = ms -> ms < t2.toEpochMilli()
                ? page(false, List.of(reading("1", t1, 100), reading("2", t2, 101)))
                : page(true, List.of(reading("3", t3, 102)));
        h.gateway.meterBgSince = ms -> page(true, List.of(mbg("m1", t1, 99)));
        h.tick();
        assertEquals(2, h.gateway.readingsSinceCalls.size());
        assertEquals(t2.toEpochMilli(), (long) h.gateway.readingsSinceCalls.get(1));
        assertTrue(h.readings.rows.keySet().containsAll(List.of("1", "2", "3")));
        assertTrue(h.entries.rows.containsKey("m1"));
        assertEquals(t3, h.state().lastReadingTime());
    }

    @Test
    public void sy5_deviceStatusChanged_fetchesLast24h() {
        Harness h = new Harness();
        h.lastModified(NsCollection.DEVICESTATUS, Harness.LM_DEVICESTATUS + 1);
        Instant[] asked = new Instant[1];
        h.gateway.deviceStatusSince = from -> {
            asked[0] = from;
            return Result.ok(List.of(new DeviceStatus("d1", NOW.minusSeconds(60), 150, "pump")));
        };
        h.tick();
        assertEquals(NOW.minus(Duration.ofHours(24)), asked[0]);
        assertEquals(1, h.deviceStatus.rows.size());
        assertEquals(List.of("deviceStatusSince"), h.gateway.fetchCalls());
    }

    @Test
    public void sy6_profileChanged_latestProfileSaved() {
        Harness h = new Harness();
        h.lastModified(NsCollection.PROFILE, Harness.LM_PROFILE + 1);
        Profile p = new Profile("p1", NOW.minus(Duration.ofDays(100)), ZoneOffset.UTC, List.of());
        h.gateway.latestProfile = () -> Result.ok(p);
        h.tick();
        assertEquals(List.of(p), h.deviceStatus.profiles);
        assertEquals(Long.valueOf(Harness.LM_PROFILE + 1), h.cursor(NsCollection.PROFILE).lastModifiedSeen());
    }

    @Test
    public void sy6_profileOkNull_nothingSaved_cursorAdvances() {
        Harness h = new Harness();
        h.lastModified(NsCollection.PROFILE, Harness.LM_PROFILE + 1);
        h.tick();
        assertTrue(h.deviceStatus.profiles.isEmpty());
        assertEquals(Long.valueOf(Harness.LM_PROFILE + 1), h.cursor(NsCollection.PROFILE).lastModifiedSeen());
    }

    @Test
    public void r21_storeFailure_cursorNotAdvanced_refetchedNextTick() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> page(true, List.of(reading("a", NOW.minusSeconds(30), 120)));
        h.readings.fail = true;
        h.tick();
        assertEquals(Long.valueOf(Harness.LM_ENTRIES), h.cursor(NsCollection.ENTRIES).lastModifiedSeen());
        h.readings.fail = false;
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(2, h.gateway.readingsSinceCalls.size());
        assertEquals(Long.valueOf(Harness.LM_ENTRIES + 1), h.cursor(NsCollection.ENTRIES).lastModifiedSeen());
    }

    @Test
    public void networkFailure_stopsFurtherCalls_noInCycleRetry() {
        Harness h = new Harness();
        java.util.Map<NsCollection, Long> m = Harness.steadyLastModified();
        m.put(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        m.put(NsCollection.TREATMENTS, Harness.LM_TREATMENTS + 1);
        h.gateway.lastModified = () -> Result.ok(m);
        h.gateway.readingsSince = ms -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, "TIMEOUT");
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.NS_NOT_RESPONDING, r.outcome());
        assertEquals(List.of("readingsSince"), h.gateway.fetchCalls());
        assertTrue(h.logger.events.contains("SYNC_FAIL:NS_NOT_RESPONDING:TIMEOUT"));
    }

    @Test
    public void lastModifiedFailure_outcomeAndNoFetch() {
        Harness h = new Harness();
        h.gateway.lastModified = () -> Result.fail(FetchOutcome.TOKEN_REJECTED, "AUTH_401");
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.TOKEN_REJECTED, r.outcome());
        assertEquals(List.of("lastModified"), h.gateway.calls);
    }

    /** ENTRIES in history mode, so readings arrive as changes that carry the server time (D-152). */
    private Harness historyHarness() {
        Harness h = new Harness();
        app.lightscout.domain.monitor.MonitorState s = Harness.steadyState(NOW);
        java.util.Map<NsCollection, app.lightscout.domain.monitor.CollectionCursor> cursors =
                new java.util.EnumMap<>(s.cursors());
        app.lightscout.domain.monitor.CollectionCursor e = cursors.get(NsCollection.ENTRIES);
        cursors.put(NsCollection.ENTRIES, new app.lightscout.domain.monitor.CollectionCursor(e.lastModifiedSeen(),
                e.hotWindowFrom(), e.lastReconcile(), e.completeFrom(), true, 1_000L));
        h.monitorState.value = new app.lightscout.domain.monitor.MonitorState(s.monitoringSince(),
                s.lastSuccessfulCheck(), s.lastCheckCompleted(), s.lastOutcome(), null, s.lastReadingTime(), 60,
                List.of(), s.lastDailyMaintenance(), cursors);
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        return h;
    }

    @Test
    public void arrivalLag_isTheServerTimeOfTheReading_notWhenWeLooked() {
        Harness h = historyHarness();
        Instant time = NOW.minusSeconds(100);   // we look 100 s after the timestamp ...
        long srv = time.plusSeconds(34).toEpochMilli();   // ... but the server had it after 34 s
        h.gateway.history = (c, cur, l) -> c == NsCollection.ENTRIES
                ? page(true, List.of(new app.lightscout.domain.model.Change(c, "a", srv, false,
                        reading("a", time, 120), null, null)))
                : page(true, List.<app.lightscout.domain.model.Change>of());
        SyncResult r = h.tick();
        assertEquals(List.of(34), h.state().recentLagSec());
        assertEquals(34, r.arrivalLagMedianSec());
        assertEquals(34, h.state().arrivalLagMedianSec());
    }

    @Test
    public void arrivalLag_ignoresClockSkewAndCatchUpUploads() {
        Harness h = historyHarness();
        Instant skew = NOW.minusSeconds(100);
        Instant late = NOW.minusSeconds(90);
        h.gateway.history = (c, cur, l) -> c == NsCollection.ENTRIES
                ? page(true, List.of(
                new app.lightscout.domain.model.Change(c, "skew", skew.minusSeconds(5).toEpochMilli(), false,
                        reading("skew", skew, 120), null, null),
                new app.lightscout.domain.model.Change(c, "late", late.plusSeconds(1800).toEpochMilli(), false,
                        reading("late", late, 125), null, null)))
                : page(true, List.<app.lightscout.domain.model.Change>of());
        SyncResult r = h.tick();
        assertEquals(List.of(), h.state().recentLagSec());
        assertEquals(60, r.arrivalLagMedianSec());
    }

    @Test
    public void arrivalLag_noServerTime_noSample_keepsThePreviousValue() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> page(true, List.<Reading>of(reading("a", NOW.minusSeconds(40), 120)));
        SyncResult r = h.tick();
        assertEquals(List.of(), h.state().recentLagSec());
        assertEquals(60, r.arrivalLagMedianSec());
    }
}
