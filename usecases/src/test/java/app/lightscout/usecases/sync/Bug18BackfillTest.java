package app.lightscout.usecases.sync;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.common.SlicedWindowFetcher;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.page;
import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * BUG-18: the SY-8 backfill must terminate, never re-download the same page when the server ignores the window,
 * and persist its progress ({@code completeFrom}) so a completed range is not fetched again.
 */
public class Bug18BackfillTest {

    private static final Duration DAY = Duration.ofHours(24);
    private static final int LIMIT = SlicedWindowFetcher.WINDOW_LIMIT;

    /** Steady harness whose ENTRIES backfill has reached only {@code now − 24 h}; treatments fully backfilled. */
    private static Harness backfilling() {
        Harness h = new Harness();
        h.monitorState.value = withEntriesCompleteFrom(Harness.steadyState(NOW), NOW.minus(DAY));
        return h;
    }

    /** {@link #backfilling()} where every window call costs the whole budget: one slice per collection per tick. */
    private static Harness backfillingOneSlicePerTick() {
        Harness h = backfilling();
        h.windowCallCostsMs(ChangeSync.BACKFILL_BUDGET.toMillis());
        return h;
    }

    private static MonitorState withEntriesCompleteFrom(MonitorState s, Instant completeFrom) {
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(s.cursors());
        CollectionCursor e = cursors.get(NsCollection.ENTRIES);
        cursors.put(NsCollection.ENTRIES, new CollectionCursor(e.lastModifiedSeen(), null, e.lastReconcile(),
                completeFrom, false, null));
        return new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.lastOutcome(), null, s.lastReadingTime(), 60, List.of(), s.lastDailyMaintenance(), cursors);
    }

    /** 5-min readings of {@code [from, to)} (at most {@code limit}), like a server honouring the window. */
    private static Result<Page<Reading>> honest(Instant from, Instant to, int limit) {
        List<Reading> out = new ArrayList<>();
        for (Instant t = from; t.isBefore(to) && out.size() < limit; t = t.plusSeconds(300)) {
            out.add(reading("r" + t.toEpochMilli(), t, 120));
        }
        return page(out.size() < limit, out);
    }

    /** The same oldest `limit` documents for every window (the RD-1 symptom: 63 × len=280712). */
    private static final List<Reading> OLDEST_PAGE = oldestPage();

    private static List<Reading> oldestPage() {
        List<Reading> out = new ArrayList<>();
        Instant t = Instant.parse("2019-01-01T00:00:00Z");
        for (int i = 0; i < LIMIT; i++) {
            out.add(reading("old" + i, t.plusSeconds(300L * i), 100));
        }
        return out;
    }

    private static void tickAfter(Harness h, Duration d) {
        h.clock.now = h.clock.now.plus(d);
        h.tick();
    }

    // ---------------------------------------------------------------- correct filter: advances and terminates

    @Test
    public void honouredWindow_backfillAdvancesOneDayPerTickWhenBudgetBuysOneSlice_andTerminatesAtCacheWindow() {
        Harness h = backfillingOneSlicePerTick();
        h.gateway.readingsWindow = Bug18BackfillTest::honest;
        h.tick();
        assertEquals(1, h.gateway.readingsWindowCalls.size());
        assertEquals(NOW.minus(DAY.multipliedBy(2)), h.gateway.readingsWindowCalls.get(0)[0]);
        assertEquals(NOW.minus(DAY), h.gateway.readingsWindowCalls.get(0)[1]);
        assertEquals(NOW.minus(DAY.multipliedBy(2)), h.cursor(NsCollection.ENTRIES).completeFrom());

        for (int i = 0; i < 40; i++) {
            tickAfter(h, Duration.ofMinutes(5));
        }
        Instant floor = h.clock.now.minus(ChangeSync.CACHE_WINDOW);
        assertFalse(h.cursor(NsCollection.ENTRIES).completeFrom().isAfter(floor));
        int calls = h.gateway.readingsWindowCalls.size();
        assertTrue("one call per day of backfill, no splits: " + calls, calls >= 30 && calls <= 32);
        // windows are contiguous towards the past, never repeating
        for (int i = 1; i < calls; i++) {
            assertEquals(h.gateway.readingsWindowCalls.get(i - 1)[0], h.gateway.readingsWindowCalls.get(i)[1]);
        }
        tickAfter(h, Duration.ofMinutes(5));
        assertEquals(calls, h.gateway.readingsWindowCalls.size());   // finished: no further call
    }

    // ---------------------------------------------------------------- server ignoring the filter

    /**
     * RD-1 reproduction: a server returning the same `limit` documents for every window. Before BUG-18 this tick
     * made 63 calls (24 h → 12 → 6 → 3 → 1.5 → 0.75 h: 1 + 2 + 4 + 8 + 16 + 32) and repeated them every tick.
     */
    @Test
    public void ignoredWindow_identicalPages_stopAfterTwoRepeats_noRepeatOnNextTick() {
        Harness h = backfilling();
        h.gateway.readingsWindow = (f, t, l) -> page(false, OLDEST_PAGE);
        h.tick();
        assertEquals("parent + two identical halves", 3, h.gateway.readingsWindowCalls.size());
        assertEquals("no mbg call after the readings were found ignored", 0, h.gateway.count("meterBgWindow"));
        assertEquals(NOW.minus(DAY), h.cursor(NsCollection.ENTRIES).completeFrom());   // not lowered
        assertTrue("no set-difference delete", h.readings.replaceWindowCalls.isEmpty());
        assertTrue(h.logger.debug.contains(ChangeSync.BACKFILL_TAG + " paused ENTRIES window-ignored"));

        for (int i = 0; i < 12; i++) {
            tickAfter(h, Duration.ofMinutes(5));
        }
        assertEquals("paused: no repeat on the next ticks", 3, h.gateway.readingsWindowCalls.size());

        tickAfter(h, ChangeSync.BACKFILL_PAUSE);   // (the daily SY-4 reconcile is due as well here)
        long backfillParents = h.gateway.readingsWindowCalls.stream()
                .filter(w -> w[0].equals(NOW.minus(DAY.multipliedBy(2))) && w[1].equals(NOW.minus(DAY))).count();
        assertEquals("the same slice is retried once after the pause", 2, backfillParents);
    }

    /** S-B F4: a cursor reset (server identity change → monitor state cleared, new monitoringSince) ends the pause. */
    @Test
    public void pause_clearedWhenCursorReset() {
        Harness h = backfilling();
        h.gateway.readingsWindow = (f, t, l) -> page(false, List.of());
        h.tick();
        tickAfter(h, Duration.ofMinutes(5));
        assertEquals("paused", 1, h.gateway.readingsWindowCalls.size());

        h.monitorState.value = MonitorState.initial();   // as after a URL / identity change (CS-5)
        h.gateway.readingsWindow = Bug18BackfillTest::honest;
        tickAfter(h, Duration.ofMinutes(5));               // SY-8 initial fill, DCR-45: backfill in the same tick
        assertTrue("backfill of the new connection is not paused: " + h.gateway.readingsWindowCalls.size(),
                h.gateway.readingsWindowCalls.size() > 1);
    }

    /** S-B F4: a {@code completeFrom} lowered elsewhere (e.g. IF-22 history load) also ends the pause. */
    @Test
    public void pause_endsWhenCompleteFromMoved() {
        Harness h = backfilling();
        h.gateway.readingsWindow = (f, t, l) -> page(false, List.of());
        h.tick();
        h.monitorState.value = withEntriesCompleteFrom(h.monitorState.value, NOW.minus(DAY.multipliedBy(3)));
        h.gateway.readingsWindow = Bug18BackfillTest::honest;
        tickAfter(h, Duration.ofMinutes(5));
        assertTrue("backfill resumes at the moved completeFrom", h.gateway.readingsWindowCalls.size() > 1);
        assertEquals(NOW.minus(DAY.multipliedBy(3)), h.gateway.readingsWindowCalls.get(1)[1]);
    }

    /** The gateway's form of the same symptom (documents outside the window dropped): one call only. */
    @Test
    public void ignoredWindow_emptyIncompletePage_stopsAfterOneCall() {
        Harness h = backfilling();
        h.gateway.readingsWindow = (f, t, l) -> page(false, List.of());
        h.tick();
        assertEquals(1, h.gateway.readingsWindowCalls.size());
        tickAfter(h, Duration.ofMinutes(5));
        assertEquals(1, h.gateway.readingsWindowCalls.size());
    }

    @Test
    public void fetcher_identicalHalves_reportWindowIgnored() {
        List<Instant[]> calls = new ArrayList<>();
        SlicedWindowFetcher.Fetched<Reading> f = SlicedWindowFetcher.fetch((from, to, l) -> {
            calls.add(new Instant[] {from, to});
            return page(false, OLDEST_PAGE);
        }, NOW.minus(Duration.ofDays(31)), NOW, LIMIT);
        assertEquals("stops in the first slice; later slices are not requested", 3, calls.size());
        assertTrue(f.windowIgnored());
        assertFalse(f.fullyComplete());
    }

    // ---------------------------------------------------------------- halving with full pages terminates

    /** Dense data (> limit per 12 h): halving down to 6 h completes the slice within the call budget. */
    @Test
    public void halving_denseHonestServer_terminatesComplete() {
        Harness h = backfillingOneSlicePerTick();
        // 1-s readings would be absurd; use a server that is full for windows longer than 6 h
        h.gateway.readingsWindow = (f, t, l) -> {
            boolean full = Duration.between(f, t).compareTo(Duration.ofHours(6)) > 0;
            List<Reading> items = new ArrayList<>();
            items.add(reading("d" + f.toEpochMilli(), f, 120));
            items.add(reading("e" + f.toEpochMilli(), f.plusSeconds(60), 121));
            return page(!full, items);
        };
        h.tick();
        assertEquals("24 h + 2 × 12 h + 4 × 6 h", 7, h.gateway.readingsWindowCalls.size());
        assertEquals(NOW.minus(DAY.multipliedBy(2)), h.cursor(NsCollection.ENTRIES).completeFrom());
    }

    /** A server whose pages are always full but different: capped per slice, then paused (no loop per tick). */
    @Test
    public void halving_alwaysFullServer_cappedPerSlice_thenPaused() {
        Harness h = backfilling();
        h.gateway.readingsWindow = (f, t, l) -> page(false, List.of(reading("p" + f.toEpochMilli() + "-"
                + t.toEpochMilli(), f, 120)));
        h.tick();
        int calls = h.gateway.readingsWindowCalls.size();
        assertTrue("capped: " + calls, calls <= SlicedWindowFetcher.MAX_CALLS_PER_SLICE);
        assertEquals(NOW.minus(DAY), h.cursor(NsCollection.ENTRIES).completeFrom());
        assertTrue(h.logger.debug.contains(ChangeSync.BACKFILL_TAG + " paused ENTRIES incomplete"));
        tickAfter(h, Duration.ofMinutes(5));
        assertEquals(calls, h.gateway.readingsWindowCalls.size());
    }

    // ---------------------------------------------------------------- DCR-45 / D-165: time budget per tick

    /** A fast server: one tick backfills the whole 31 days (the fill after an install or a long pause). */
    @Test
    public void budget_fastServer_oneTickBackfillsToTheCacheWindow() {
        Harness h = backfilling();
        h.gateway.readingsWindow = Bug18BackfillTest::honest;
        h.tick();
        Instant floor = NOW.minus(ChangeSync.CACHE_WINDOW);
        assertFalse(h.cursor(NsCollection.ENTRIES).completeFrom().isAfter(floor));
        assertEquals("newest slice first", NOW.minus(DAY), h.gateway.readingsWindowCalls.get(0)[1]);
        int calls = h.gateway.readingsWindowCalls.size();
        assertTrue("one call per day: " + calls, calls >= 29 && calls <= 31);
        tickAfter(h, Duration.ofMinutes(1));
        assertEquals("finished: nothing more next tick", calls, h.gateway.readingsWindowCalls.size());
    }

    /** A slow server (5 s per call, an entries slice = readings + meter BG = 10 s): 15 s buys two slices, then stops. */
    @Test
    public void budget_slowServer_stopsAfterFifteenSeconds_nextTickContinues() {
        Harness h = backfilling();
        h.windowCallCostsMs(5_000);
        h.gateway.readingsWindow = Bug18BackfillTest::honest;
        h.tick();
        assertEquals("slice at 0 s and at 10 s run, the check at 20 s stops", 2, h.gateway.readingsWindowCalls.size());
        assertEquals(NOW.minus(DAY.multipliedBy(3)), h.cursor(NsCollection.ENTRIES).completeFrom());
        assertTrue(h.logger.debug.contains(ChangeSync.BACKFILL_TAG + " budget spent"));
        tickAfter(h, Duration.ofMinutes(1));
        assertEquals("the next tick continues where it stopped", 4, h.gateway.readingsWindowCalls.size());
        assertEquals(NOW.minus(DAY.multipliedBy(3)), h.gateway.readingsWindowCalls.get(1)[0]);
        assertEquals(NOW.minus(DAY.multipliedBy(3)), h.gateway.readingsWindowCalls.get(2)[1]);
    }

    /** Treatments (site / sensor changes) are not starved by a long readings history: the collections take turns. */
    @Test
    public void budget_collectionsTakeTurnsSliceBySlice() {
        Harness h = new Harness();
        MonitorState s = withEntriesCompleteFrom(Harness.steadyState(NOW), NOW.minus(DAY));
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(s.cursors());
        CollectionCursor t = cursors.get(NsCollection.TREATMENTS);
        cursors.put(NsCollection.TREATMENTS, new CollectionCursor(t.lastModifiedSeen(), null, t.lastReconcile(),
                NOW.minus(DAY), false, null));
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.lastOutcome(), null, s.lastReadingTime(), 60, List.of(), s.lastDailyMaintenance(), cursors);
        h.windowCallCostsMs(5_000);   // entries slice 10 s, treatments slice 5 s: 15 s = one slice of each
        h.gateway.readingsWindow = Bug18BackfillTest::honest;
        h.tick();
        assertEquals(NOW.minus(DAY.multipliedBy(2)), h.cursor(NsCollection.ENTRIES).completeFrom());
        assertEquals(NOW.minus(DAY.multipliedBy(2)), h.cursor(NsCollection.TREATMENTS).completeFrom());
        List<String> window = h.gateway.calls.stream().filter(c -> c.endsWith("Window")).toList();
        assertEquals(List.of("readingsWindow", "meterBgWindow", "entriesWindow"), window);
    }

    /** A network failure in the backfill ends the backfill of the tick (no further slices, no other collection). */
    @Test
    public void budget_failureStopsTheTickBackfill() {
        Harness h = backfilling();
        h.gateway.readingsWindow = (f, t, l) -> Result.fail(app.lightscout.domain.port.FetchOutcome.NO_INTERNET,
                "NO_NETWORK");
        h.tick();
        assertEquals(1, h.gateway.readingsWindowCalls.size());
        assertEquals(0, h.gateway.count("entriesWindow"));
        assertEquals(NOW.minus(DAY), h.cursor(NsCollection.ENTRIES).completeFrom());
    }

    // ---------------------------------------------------------------- progress persisted across ticks

    /** {@code completeFrom} reaches ST-04 after each tick; a new process continues from it. */
    @Test
    public void progressPersisted_newProcessContinuesWhereItStopped() {
        Harness h = backfillingOneSlicePerTick();
        h.gateway.readingsWindow = Bug18BackfillTest::honest;
        h.tick();
        tickAfter(h, Duration.ofMinutes(5));
        Instant persisted = h.monitorState.value.cursors().get(NsCollection.ENTRIES).completeFrom();
        assertEquals(NOW.minus(DAY.multipliedBy(3)), persisted);

        // "process restart": new cycle over the persisted monitor state
        Harness restarted = new Harness();
        restarted.clock.now = h.clock.now.plus(Duration.ofMinutes(5));
        restarted.windowCallCostsMs(ChangeSync.BACKFILL_BUDGET.toMillis());
        restarted.monitorState.value = h.monitorState.value;
        restarted.gateway.readingsWindow = Bug18BackfillTest::honest;
        restarted.tick();
        assertEquals(1, restarted.gateway.readingsWindowCalls.size());
        assertEquals(persisted, restarted.gateway.readingsWindowCalls.get(0)[1]);   // no completed day again
        assertEquals(persisted.minus(DAY), restarted.gateway.readingsWindowCalls.get(0)[0]);
    }

    // ---------------------------------------------------------------- Q-101 (S-B F3): backfill after publish

    /** Alarm evaluation (IF-16), the snapshot and the step-6 write are done before any backfill call. */
    @Test
    public void q101_backfillRunsAfterEvaluatePublishAndPersist() {
        Harness h = backfillingOneSlicePerTick();
        int[] appliedAtCall = {-1};
        int[] snapshotsAtCall = {-1};
        Instant[] lastCheckAtCall = {null};
        int appliedBefore = h.alarmController.applied.size();
        int snapshotsBefore = h.snapshots.size();
        h.gateway.readingsWindow = (f, t, l) -> {
            if (appliedAtCall[0] < 0) {
                appliedAtCall[0] = h.alarmController.applied.size();
                snapshotsAtCall[0] = h.snapshots.size();
                lastCheckAtCall[0] = h.monitorState.value.lastCheckCompleted();
            }
            return honest(f, t, l);
        };
        h.tick();
        assertEquals("IF-16 applied before the backfill", appliedBefore + 1, appliedAtCall[0]);
        assertTrue("snapshot published before the backfill", snapshotsAtCall[0] > snapshotsBefore);
        assertEquals("step 6 persisted before the backfill", NOW, lastCheckAtCall[0]);
        // the backfill progress is persisted by the second write of the same tick
        assertEquals(NOW.minus(DAY.multipliedBy(2)), h.cursor(NsCollection.ENTRIES).completeFrom());
        assertEquals(NOW, h.state().lastCheckCompleted());
    }

    /** A network failure of the backfill (after publish) does not change the tick outcome. */
    @Test
    public void q101_backfillNetworkFailure_tickStillOk() {
        Harness h = backfilling();
        h.gateway.readingsWindow = (f, t, l) -> Result.fail(app.lightscout.domain.port.FetchOutcome.NO_INTERNET,
                "NO_NETWORK");
        app.lightscout.usecases.SyncResult r = h.tick();
        assertEquals(app.lightscout.domain.port.FetchOutcome.OK, r.outcome());
        assertEquals(app.lightscout.domain.port.FetchOutcome.OK, h.state().lastOutcome());
        assertEquals(NOW, h.state().lastSuccessfulCheck());
        assertTrue(h.logger.events.contains("SYNC_OK"));
        assertEquals(NOW.minus(DAY), h.cursor(NsCollection.ENTRIES).completeFrom());   // not lowered
    }

    /** A failed second monitor-state write (after publish) changes neither the outcome nor the alarm decision. */
    @Test
    public void q101_secondWriteFails_outcomeAndAlarmUnchanged() {
        Harness h = backfilling();
        int[] applied = {0};
        h.gateway.readingsWindow = (f, t, l) -> {
            h.monitorState.failPut = true;   // the step-6 write already happened
            applied[0] = h.alarmController.applied.size();
            return honest(f, t, l);
        };
        app.lightscout.usecases.SyncResult r = h.tick();
        assertEquals(app.lightscout.domain.port.FetchOutcome.OK, r.outcome());
        assertTrue(h.logger.events.contains("SYNC_OK"));
        assertEquals("no further IF-16 call after the backfill", applied[0], h.alarmController.applied.size());
        assertEquals(NOW, h.monitorState.value.lastCheckCompleted());              // step 6 kept
        assertEquals(NOW.minus(DAY), h.monitorState.value.cursors().get(NsCollection.ENTRIES).completeFrom());
    }

    /** N4: a persistently failing monitor-state write does not repeat the daily maintenance on every tick. */
    @Test
    public void n4_failingPut_dailyMaintenanceOncePerDayInProcess() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.lastOutcome(), null, s.lastReadingTime(), 60, List.of(), NOW.minus(DAY), s.cursors());
        h.monitorState.failPut = true;
        h.tick();
        tickAfter(h, Duration.ofMinutes(5));
        tickAfter(h, Duration.ofMinutes(5));
        long daily = h.logger.events.stream().filter(e -> e.startsWith("DAILY_CHECK")).count();
        assertEquals(1, daily);
        assertEquals("the stored value never changed", NOW.minus(DAY), h.monitorState.value.lastDailyMaintenance());
    }

    /** N4: the in-process guard does not apply to a reset monitor state (new connection). */
    @Test
    public void n4_guardIgnoredAfterMonitorStateReset() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.lastOutcome(), null, s.lastReadingTime(), 60, List.of(), NOW.minus(DAY), s.cursors());
        h.tick();
        h.monitorState.value = MonitorState.initial();
        tickAfter(h, Duration.ofMinutes(5));
        long daily = h.logger.events.stream().filter(e -> e.startsWith("DAILY_CHECK")).count();
        assertEquals(2, daily);
    }
}
