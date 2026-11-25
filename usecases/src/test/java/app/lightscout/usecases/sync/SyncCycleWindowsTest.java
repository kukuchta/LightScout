package app.lightscout.usecases.sync;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.SyncResult;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.ownTreatment;
import static app.lightscout.usecases.fakes.Fakes.page;
import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Fakes.treatment;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** SY-3 a/b/c, C-8, SY-4, SY-7, SY-8, eviction, daily checks R-67 (CP-02 §9). */
public class SyncCycleWindowsTest {

    private static final Duration DAY = Duration.ofHours(24);

    // ------------------------------------------------------------------ SY-3

    @Test
    public void sy3a_hotWindowIn24hSlices_completeSliceMissingCachedIdIsDeleted() {
        Harness h = new Harness();
        h.lastModified(NsCollection.TREATMENTS, Harness.LM_TREATMENTS + 1);
        Instant tx = NOW.minus(Duration.ofHours(30));
        Instant tk = NOW.minus(Duration.ofHours(10));
        h.entries.put(treatment("X", tx, EntryType.CARBS), treatment("K", tk, EntryType.CARBS));
        Entry keep = treatment("K", tk, EntryType.CARBS);
        h.gateway.entriesWindow = (f, t, l) -> page(true,
                !tk.isBefore(f) && tk.isBefore(t) ? List.of(keep) : List.of());
        h.tick();
        for (Instant[] call : h.gateway.entriesWindowCalls) {
            assertTrue(Duration.between(call[0], call[1]).compareTo(DAY) <= 0);
        }
        assertEquals(NOW.minus(Duration.ofHours(48)), h.gateway.entriesWindowCalls.get(0)[0]);
        assertFalse("X missing from a complete slice → deleted", h.entries.rows.containsKey("X"));
        assertTrue(h.entries.rows.containsKey("K"));
        assertEquals(EntryOrigin.TREATMENTS, h.entries.replaceWindowCalls.get(0)[0]);
    }

    @Test
    public void sy3b_c8_sliceReturnsLimit_splitAgain_noDeleteFromIncompletePage() {
        Harness h = new Harness();
        h.lastModified(NsCollection.TREATMENTS, Harness.LM_TREATMENTS + 1);
        Instant tx = NOW.minus(Duration.ofHours(30));
        h.entries.put(treatment("X", tx, EntryType.CARBS));
        // every slice longer than 1 h is "full"; 1 h-or-shorter slices are full too (pathological server)
        h.gateway.entriesWindow = (f, t, l) -> page(false, List.of(treatment("N" + f.toEpochMilli(), f,
                EntryType.CARBS)));
        h.tick();
        assertTrue(h.gateway.entriesWindowCalls.size() > 3);   // split again
        Instant[] smallest = h.gateway.entriesWindowCalls.stream()
                .min((a, b) -> Duration.between(a[0], a[1]).compareTo(Duration.between(b[0], b[1]))).get();
        assertTrue(Duration.between(smallest[0], smallest[1]).compareTo(Duration.ofHours(1)) <= 0);
        assertTrue("no set-difference delete from an incomplete page", h.entries.replaceWindowCalls.isEmpty());
        assertTrue(h.entries.rows.containsKey("X"));
        assertTrue(h.entries.rows.size() > 1);                  // items still upserted
    }

    @Test
    public void sy3c_networkFailureMidWindow_upsertsKept_noDeletes_outcome() {
        Harness h = new Harness();
        h.lastModified(NsCollection.TREATMENTS, Harness.LM_TREATMENTS + 1);
        h.entries.put(treatment("X", NOW.minus(Duration.ofHours(40)), EntryType.CARBS));
        Entry fresh = treatment("F", NOW.minus(Duration.ofHours(47)), EntryType.CARBS);
        int[] n = {0};
        h.gateway.entriesWindow = (f, t, l) -> n[0]++ == 0 ? page(true, List.of(fresh))
                : Result.fail(FetchOutcome.NO_INTERNET, "NO_NETWORK");
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.NO_INTERNET, r.outcome());
        assertTrue(h.entries.rows.containsKey("F"));
        assertTrue(h.entries.rows.containsKey("X"));
        assertTrue(h.entries.replaceWindowCalls.isEmpty());
        assertEquals(Long.valueOf(Harness.LM_TREATMENTS), h.cursor(NsCollection.TREATMENTS).lastModifiedSeen());

        Harness h2 = new Harness();
        h2.lastModified(NsCollection.TREATMENTS, Harness.LM_TREATMENTS + 1);
        int[] m = {0};
        h2.gateway.entriesWindow = (f, t, l) -> m[0]++ == 0 ? page(true, List.of(fresh))
                : Result.fail(FetchOutcome.NS_NOT_RESPONDING, "HTTP_5XX");
        assertEquals(FetchOutcome.NS_NOT_RESPONDING, h2.tick().outcome());
        assertTrue(h2.entries.replaceWindowCalls.isEmpty());
    }

    // ------------------------------------------------------------------ SY-4 / daily

    @Test
    public void sy4_firstTickAfter0300_reconciles31DaysOnce_secondTickSameDayDoesNot() {
        Harness h = new Harness();
        Instant morning = Instant.parse("2026-10-15T03:05:00Z");
        h.clock.now = morning;
        h.monitorState.value = withDaily(Harness.steadyState(morning), Instant.parse("2026-10-14T03:10:00Z"));
        h.tick();
        long readingCalls = h.gateway.count("readingsWindow");
        assertEquals(31, readingCalls);
        assertEquals(31, h.gateway.count("meterBgWindow"));
        assertEquals(31, h.gateway.count("entriesWindow"));
        assertEquals(morning.minus(Duration.ofDays(31)), h.gateway.readingsWindowCalls.get(0)[0]);
        assertEquals(morning, h.cursor(NsCollection.ENTRIES).lastReconcile());
        assertEquals(morning, h.state().lastDailyMaintenance());
        assertEquals(31, h.readings.replaceWindowCalls.size());

        h.clock.advanceMinutes(5);
        h.tick();
        assertEquals(readingCalls, h.gateway.count("readingsWindow"));
    }

    @Test
    public void daily_beforeThreeLocal_notDueIfRanYesterdayAfterThree() {
        assertFalse(DailyMaintenance.isDue(Instant.parse("2026-10-15T02:59:00Z"), java.time.ZoneOffset.UTC,
                Instant.parse("2026-10-14T03:01:00Z")));
        assertTrue(DailyMaintenance.isDue(Instant.parse("2026-10-15T03:00:00Z"), java.time.ZoneOffset.UTC,
                Instant.parse("2026-10-14T03:01:00Z")));
        assertTrue(DailyMaintenance.isDue(Instant.parse("2026-10-15T03:00:00Z"), java.time.ZoneOffset.UTC, null));
    }

    @Test
    public void eviction_rowsOlderThan31DaysEvicted() {
        Harness h = new Harness();
        h.monitorState.value = withDaily(Harness.steadyState(NOW), NOW.minus(DAY));
        Instant old = NOW.minus(Duration.ofDays(32));
        Reading recent = reading("new", NOW.minusSeconds(180), 100);
        h.readings.put(reading("old", old, 100), recent);
        // the daily SY-4 reconcile runs too: the server still has the recent reading
        h.gateway.readingsWindow = (f, t, l) -> page(true,
                !recent.time().isBefore(f) && recent.time().isBefore(t) ? List.of(recent) : List.of());
        h.entries.put(treatment("oldE", old, EntryType.CARBS));
        h.notified.rows.put("pa", old);
        h.tick();
        Instant cutoff = NOW.minus(Duration.ofDays(31));
        assertEquals(List.of(cutoff), h.readings.evictCalls);
        assertEquals(List.of(cutoff), h.entries.evictCalls);
        assertEquals(List.of(cutoff), h.deviceStatus.evictCalls);
        assertEquals(List.of(cutoff), h.notified.evictCalls);
        assertFalse(h.readings.rows.containsKey("old"));
        assertTrue(h.readings.rows.containsKey("new"));
        assertFalse(h.entries.rows.containsKey("oldE"));
        assertFalse(h.notified.rows.containsKey("pa"));
    }

    @Test
    public void r67_dailyVersionTooOld_stickyAcrossOkTicks_clearedWhenCheckPasses() {
        Harness h = new Harness();
        h.monitorState.value = withDaily(Harness.steadyState(NOW), NOW.minus(DAY));
        h.gateway.version = u -> Result.ok(new ServerInfo("14.2.0", "3.0.0"));
        h.tick();
        assertEquals(FetchOutcome.SERVER_TOO_OLD, h.state().connectionProblem());
        assertEquals(FetchOutcome.OK, h.state().lastOutcome());
        assertEquals(FetchOutcome.SERVER_TOO_OLD, h.status.current().connectionProblem());
        // next OK tick: still sticky (version re-checked every tick while SERVER_TOO_OLD)
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(FetchOutcome.SERVER_TOO_OLD, h.state().connectionProblem());
        // server upgraded → the re-check clears it
        h.gateway.version = u -> Result.ok(new ServerInfo("15.0.9", "3.0.0"));
        h.clock.advanceMinutes(1);
        h.tick();
        assertNull(h.state().connectionProblem());
    }

    @Test
    public void r67_dailyChecks_permissionAndIdentity() {
        Harness h = new Harness();
        h.monitorState.value = withDaily(Harness.steadyState(NOW), NOW.minus(DAY));
        h.gateway.permissions = () -> Result.ok(new Permissions(Map.of(NsCollection.ENTRIES, "r",
                NsCollection.TREATMENTS, "cr")));
        h.tick();
        // treatments "cr": read passes, update and delete are missing
        assertEquals(FetchOutcome.PERMISSION_WRITE_MISSING, h.state().connectionProblem());
        h.clock.advanceMinutes(1);
        h.tick();   // OK tick does not clear it
        assertEquals(FetchOutcome.PERMISSION_WRITE_MISSING, h.state().connectionProblem());

        Harness hr = new Harness();
        hr.monitorState.value = withDaily(Harness.steadyState(NOW), NOW.minus(DAY));
        hr.gateway.permissions = () -> Result.ok(new Permissions(Map.of(NsCollection.ENTRIES, "",
                NsCollection.TREATMENTS, "crud")));
        hr.tick();
        assertEquals(FetchOutcome.PERMISSION_MISSING, hr.state().connectionProblem());

        Harness h2 = new Harness();
        h2.monitorState.value = withDaily(Harness.steadyState(NOW), NOW.minus(DAY));
        h2.gateway.version = u -> Result.ok(new ServerInfo("15.0.9", "1.0"));
        h2.tick();
        // a Nightscout without API v3 is reported as too old on Home (S-06 keeps its own copy)
        assertEquals(FetchOutcome.SERVER_TOO_OLD, h2.state().connectionProblem());

        Harness h3 = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        h3.monitorState.value = withDaily(new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(),
                s.lastCheckCompleted(), s.lastOutcome(), FetchOutcome.PERMISSION_MISSING, s.lastReadingTime(),
                60, List.of(), s.lastDailyMaintenance(), s.cursors()), NOW.minus(DAY));
        h3.tick();   // a passing daily check clears it
        assertNull(h3.state().connectionProblem());
    }

    // ------------------------------------------------------------------ SY-7

    @Test
    public void sy7_probeUsesNewestNonLightScoutDoc_historyMode_ownDocIgnored() {
        Harness h = new Harness();
        h.monitorState.value = withDaily(Harness.steadyState(NOW), NOW.minus(DAY));
        Instant tOther = NOW.minus(Duration.ofHours(5));
        Instant tOwn = NOW.minus(Duration.ofHours(1));
        h.entries.put(treatment("other", tOther, EntryType.CARBS), ownTreatment("own", tOwn, EntryType.CARBS));
        h.readings.put(reading("r1", NOW.minusSeconds(180), 100));
        List<long[]> probes = new ArrayList<>();
        h.gateway.history = (c, cur, l) -> {
            probes.add(new long[] {c.ordinal(), cur});
            if (c == NsCollection.TREATMENTS && cur == tOther.toEpochMilli() - 1) {
                return page(true, List.of(new Change(c, "other", 5L, false, null,
                        treatment("other", tOther, EntryType.CARBS), null)));
            }
            return page(true, List.of());
        };
        h.tick();
        assertTrue(probes.stream().anyMatch(p -> p[0] == NsCollection.TREATMENTS.ordinal()
                && p[1] == tOther.toEpochMilli() - 1));
        assertFalse("own document never used", probes.stream().anyMatch(p -> p[1] == tOwn.toEpochMilli() - 1));
        assertTrue(h.cursor(NsCollection.TREATMENTS).historyCapable());
        assertNotNull(h.cursor(NsCollection.TREATMENTS).historyCursorMs());
        assertFalse("history returned nothing for entries → windowed", h.cursor(NsCollection.ENTRIES).historyCapable());
    }

    @Test
    public void sy7_historyMode_isValidFalse_deletesAllStoredForms_andUpserts() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(s.cursors());
        CollectionCursor t = cursors.get(NsCollection.TREATMENTS);
        cursors.put(NsCollection.TREATMENTS, new CollectionCursor(t.lastModifiedSeen(), t.hotWindowFrom(),
                t.lastReconcile(), t.completeFrom(), true, 1_900L));
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.lastOutcome(), null, s.lastReadingTime(), 60, List.of(), s.lastDailyMaintenance(), cursors);
        Instant time = NOW.minus(Duration.ofHours(3));
        h.entries.put(treatment("X#carbs", time, EntryType.CARBS), treatment("X#insulin", time, EntryType.BOLUS),
                treatment("Y", time, EntryType.CARBS));
        h.lastModified(NsCollection.TREATMENTS, 2_100L);
        Entry ySplit = treatment("Y#carbs", time, EntryType.CARBS);
        h.gateway.history = (c, cur, l) -> page(true, List.of(
                new Change(NsCollection.TREATMENTS, "X", 2_050L, true, null, null, null),
                new Change(NsCollection.TREATMENTS, "Y", 2_060L, false, null, ySplit, null)));
        h.tick();
        assertEquals(List.of("history"), h.gateway.fetchCalls());
        assertEquals(1_900L, h.gateway.historyCalls.get(0)[1]);
        assertFalse(h.entries.rows.containsKey("X#carbs"));
        assertFalse(h.entries.rows.containsKey("X#insulin"));
        assertFalse("single form replaced by the split form", h.entries.rows.containsKey("Y"));
        assertTrue(h.entries.rows.containsKey("Y#carbs"));
        assertEquals(Long.valueOf(2_060L), h.cursor(NsCollection.TREATMENTS).historyCursorMs());
        assertEquals(Long.valueOf(2_100L), h.cursor(NsCollection.TREATMENTS).lastModifiedSeen());
    }

    // ------------------------------------------------------------------ SY-8

    @Test
    public void sy8_afterConnect_last24hFirst_thenBackfillTo31Days() {
        Harness h = new Harness();
        h.monitorState.value = MonitorState.initial();
        h.gateway.readingsSince = ms -> page(true, List.of(reading("r", NOW.minusSeconds(120), 110)));
        h.tick();
        // DCR-45: the fill tick continues with the backfill inside its time budget, newest slice first
        assertEquals(List.of(NOW.minus(DAY).toEpochMilli()), h.gateway.readingsSinceCalls);
        assertEquals(NOW.minus(DAY), h.gateway.readingsWindowCalls.get(0)[1]);
        assertEquals(NOW.minus(Duration.ofHours(48)), h.gateway.readingsWindowCalls.get(0)[0]);
        assertEquals(NOW, h.state().monitoringSince());
        Instant floor = NOW.minus(Duration.ofDays(31));
        assertFalse(h.cursor(NsCollection.ENTRIES).completeFrom().isAfter(floor));
        assertFalse(h.cursor(NsCollection.TREATMENTS).completeFrom().isAfter(floor));
        long windows = h.gateway.count("readingsWindow");
        assertTrue(windows >= 30 && windows <= 32);
        for (int i = 1; i < h.gateway.readingsWindowCalls.size(); i++) {   // contiguous towards the past
            assertEquals(h.gateway.readingsWindowCalls.get(i - 1)[0], h.gateway.readingsWindowCalls.get(i)[1]);
        }
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals(windows, h.gateway.count("readingsWindow"));   // backfill finished
    }

    @Test
    public void sy8_backfillOnlyOnOkTicks() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(s.cursors());
        CollectionCursor e = cursors.get(NsCollection.ENTRIES);
        cursors.put(NsCollection.ENTRIES, new CollectionCursor(e.lastModifiedSeen(), null, null, NOW.minus(DAY),
                false, null));
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.lastOutcome(), null, s.lastReadingTime(), 60, List.of(), s.lastDailyMaintenance(), cursors);
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> Result.fail(FetchOutcome.NO_INTERNET, "NO_NETWORK");
        h.tick();
        assertEquals(0, h.gateway.count("readingsWindow"));
    }

    private static MonitorState withDaily(MonitorState s, Instant lastDaily) {
        return new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(), s.lastOutcome(),
                s.connectionProblem(), s.lastReadingTime(), s.arrivalLagMedianSec(), s.recentLagSec(), lastDaily,
                s.cursors());
    }

    @SuppressWarnings("unused")
    private static List<Reading> none() {
        return List.of();
    }
}
