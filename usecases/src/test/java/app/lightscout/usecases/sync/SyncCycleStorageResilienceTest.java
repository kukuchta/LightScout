package app.lightscout.usecases.sync;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.SyncResult;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.page;
import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Architect rulings on SS-05 A-7, A-13 and A-16 (store failures, reconcile while backfilling). */
public class SyncCycleStorageResilienceTest {

    @Test
    public void a7_getFailsAfterEarlierRead_lastCheckCompletedStillPersisted_cursorsKept() {
        Harness h = new Harness();
        h.tick();                                   // base read successfully in this process
        MonitorState before = h.state();
        h.monitorState.failGet = true;
        h.clock.advanceMinutes(1);
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.STORAGE_ERROR, r.outcome());
        assertEquals(2, h.monitorState.puts.size());
        MonitorState after = h.state();
        assertEquals(NOW.plusSeconds(60), after.lastCheckCompleted());   // no false NOT_RUNNING (F2)
        assertEquals(before.cursors(), after.cursors());
        assertEquals(before.monitoringSince(), after.monitoringSince());
        assertEquals(before.lastSuccessfulCheck(), after.lastSuccessfulCheck());
    }

    @Test
    public void a7_getFailsWithoutEarlierRead_initialFallbackNeverWritten() {
        Harness h = new Harness();
        h.monitorState.failGet = true;
        h.tick();
        assertTrue(h.monitorState.puts.isEmpty());
        h.clock.advanceMinutes(1);
        h.tick();                                   // still no authoritative base → still no put
        assertTrue(h.monitorState.puts.isEmpty());
    }

    @Test
    public void a13_readingWritesFail_al1FiresFromFetchedReadings() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> page(true, List.of(reading("low", NOW.minusSeconds(30), 60)));
        h.readings.fail = true;                     // ST-01 reads and writes fail
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.STORAGE_ERROR, r.outcome());
        AlarmCommand.Start start = (AlarmCommand.Start) h.alarmController.last().get(0);
        assertEquals(AlarmType.LOW, start.type());
        assertEquals(Integer.valueOf(60), start.view().sgv());
        assertEquals(NOW.minusSeconds(30), h.state().lastReadingTime());
    }

    @Test
    public void a13_readingStoreFails_realGap_al2NoDataStillFires() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                FetchOutcome.OK, null, NOW.minus(Duration.ofMinutes(25)), 60, List.of(), s.lastDailyMaintenance(),
                s.cursors());
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);   // server answers, but nothing new
        h.readings.fail = true;
        h.tick();
        AlarmCommand.Start start = (AlarmCommand.Start) h.alarmController.last().get(0);
        assertEquals(AlarmType.NO_DATA, start.type());
    }

    @Test
    public void a16_dailyReconcileRunsOverFilledRangeWhileBackfillIncomplete() {
        Harness h = new Harness();
        Instant morning = Instant.parse("2026-10-15T03:05:00Z");
        h.clock.now = morning;
        MonitorState s = Harness.steadyState(morning);
        Instant completeFrom = morning.minus(Duration.ofDays(5));
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(s.cursors());
        cursors.put(NsCollection.ENTRIES, new CollectionCursor(Harness.LM_ENTRIES, null, null, completeFrom,
                false, null));
        cursors.put(NsCollection.TREATMENTS, new CollectionCursor(Harness.LM_TREATMENTS, null, null, completeFrom,
                false, null));
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                FetchOutcome.OK, null, s.lastReadingTime(), 60, List.of(), Instant.parse("2026-10-14T03:10:00Z"),
                cursors);
        h.windowCallCostsMs(ChangeSync.BACKFILL_BUDGET.toMillis());   // DCR-45: the budget buys one backfill slice
        h.tick();
        // reconcile: 5 slices from completeFrom; backfill: 1 slice below completeFrom
        assertEquals(6, h.gateway.count("readingsWindow"));
        assertEquals(completeFrom, h.gateway.readingsWindowCalls.get(0)[0]);
        assertEquals(morning, h.cursor(NsCollection.ENTRIES).lastReconcile());
        assertEquals(morning, h.cursor(NsCollection.TREATMENTS).lastReconcile());
        assertEquals(completeFrom.minus(Duration.ofHours(24)), h.cursor(NsCollection.ENTRIES).completeFrom());
    }

    @Test
    public void a16_firstFillTick_noReconcile() {
        Harness h = new Harness();
        h.monitorState.value = MonitorState.initial();
        h.gateway.lastModified = () -> Result.ok(Harness.steadyLastModified());
        h.windowCallCostsMs(ChangeSync.BACKFILL_BUDGET.toMillis());   // DCR-45: the budget buys one backfill slice
        h.tick();
        // no reconcile in the fill tick (A-16): the only window call is the first backfill slice, just below the fill
        assertEquals(1, h.gateway.count("readingsWindow"));
        assertEquals(NOW.minus(Duration.ofHours(24)), h.gateway.readingsWindowCalls.get(0)[1]);
        assertEquals(3, h.gateway.count("entriesWindow"));   // hot window only
    }
}
