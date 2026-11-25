package app.lightscout.usecases.history;

import app.lightscout.domain.model.BasalSegment;
import app.lightscout.domain.model.BasalStep;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.statistics.PeriodFactory;
import app.lightscout.usecases.Segment;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.fakes.Cp02bFakes;
import app.lightscout.usecases.fakes.Fakes;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.mbg;
import static app.lightscout.usecases.fakes.Fakes.page;
import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Fakes.treatment;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** HL-1…HL-4 (coverage rule 04 §8, C-8 slicing, R-21 lowering on the monitor executor), R-26 basal (CP-02 §9). */
public class HistoryLoaderTest {

    private static final Instant NOW = Harness.NOW;

    private Cp02bFakes.FakeGateway gateway;
    private Fakes.FakeReadingStore readings;
    private Fakes.FakeEntryStore entries;
    private Fakes.FakeDeviceStatusStore deviceStatus;
    private Fakes.FakeMonitorStateStore monitorState;
    private MonitorStateMemory monitorMemory;
    private Fakes.FakeConnectivity connectivity;
    private Fakes.QueueExecutor io;
    private Fakes.QueueExecutor monitor;
    private DefaultHistoryLoader loader;
    private app.lightscout.usecases.common.ServerEpoch epoch;

    @Before
    public void setUp() {
        gateway = new Cp02bFakes.FakeGateway();
        readings = new Fakes.FakeReadingStore();
        entries = new Fakes.FakeEntryStore();
        deviceStatus = new Fakes.FakeDeviceStatusStore();
        monitorState = new Fakes.FakeMonitorStateStore();
        monitorState.value = Harness.steadyState(NOW);   // backfill complete (completeFrom = now − 40 d)
        monitorMemory = new MonitorStateMemory();
        connectivity = new Fakes.FakeConnectivity();
        io = new Fakes.QueueExecutor();
        monitor = new Fakes.QueueExecutor();
        epoch = new app.lightscout.usecases.common.ServerEpoch();
        loader = new DefaultHistoryLoader(io, monitor, gateway, readings, entries, deviceStatus, monitorState,
                monitorMemory, connectivity, new Fakes.FakeClock(NOW), epoch);
    }

    private void completeFrom(Instant entriesFrom, Instant treatmentsFrom) {
        MonitorState s = monitorState.value;
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(NsCollection.class);
        cursors.putAll(s.cursors());
        cursors.put(NsCollection.ENTRIES, new CollectionCursor(1L, null, null, entriesFrom, false, null));
        cursors.put(NsCollection.TREATMENTS, new CollectionCursor(2L, null, null, treatmentsFrom, false, null));
        monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.lastOutcome(), s.connectionProblem(), s.lastReadingTime(), s.arrivalLagMedianSec(), s.recentLagSec(),
                s.lastDailyMaintenance(), cursors);
    }

    private Instant cursorFrom(NsCollection c) {
        return monitorState.value.cursors().get(c).completeFrom();
    }

    private Result<Segment> segment(Instant from, Instant to) {
        Cp02bFakes.Recorder<Segment> r = new Cp02bFakes.Recorder<>();
        loader.loadSegment(from, to, r);
        io.runAll();
        return r.only();
    }

    private Result<List<Reading>> period(app.lightscout.domain.statistics.Period p) {
        Cp02bFakes.Recorder<List<Reading>> r = new Cp02bFakes.Recorder<>();
        loader.loadPeriod(p, r);
        io.runAll();
        return r.only();
    }

    private Result<Segment> cachedSegment(Instant from, Instant to) {
        Cp02bFakes.Recorder<Segment> r = new Cp02bFakes.Recorder<>();
        loader.loadCachedSegment(from, to, r);
        io.runAll();
        return r.only();
    }

    private static Entry tempBasal(String id, Instant time, int rateMilli, int minutes) {
        return new Entry(id, EntryOrigin.TREATMENTS, EntryType.TEMP_BASAL, time, null, null, null, rateMilli, minutes,
                null, "pump", "loop", "Temp Basal", false, 1L);
    }

    // ------------------------------------------------------------------ HL-1 / HL-4

    @Test
    public void hl1_rangeInsideCoverage_servedFromCacheWhileOffline() {
        connectivity.online = false;
        readings.put(reading("r1", NOW.minus(Duration.ofHours(2)), 110), reading("r2", NOW.minus(Duration.ofHours(1)), 120));
        entries.put(treatment("c1", NOW.minus(Duration.ofMinutes(90)), EntryType.CARBS));
        Result<Segment> r = segment(NOW.minus(Duration.ofHours(6)), NOW);
        assertTrue(r.ok());
        assertEquals(List.of("r1", "r2"), r.value().readings().stream().map(Reading::id).toList());
        assertEquals(List.of("c1"), r.value().entries().stream().map(Entry::id).toList());
        assertTrue(r.value().basal().isEmpty());   // no temp basal → band hidden (D-017)
        assertTrue(gateway.calls.isEmpty());
        assertTrue(monitor.queue.isEmpty());
    }

    @Test
    public void cacheOnlySegment_unknownCoverageOffline_returnsAvailableRowsWithoutGatewayOrCoverageChanges() {
        monitorState.value = MonitorState.initial();
        connectivity.online = false;
        readings.put(reading("cached", NOW.minus(Duration.ofHours(5)), 112));
        entries.put(treatment("cached-entry", NOW.minus(Duration.ofHours(4)), EntryType.CARBS));

        Result<Segment> result = cachedSegment(NOW.minus(Duration.ofDays(7)), NOW);

        assertTrue(result.ok());
        assertEquals(List.of("cached"), result.value().readings().stream().map(Reading::id).toList());
        assertEquals(List.of("cached-entry"), result.value().entries().stream().map(Entry::id).toList());
        assertTrue("local cache availability is not a completeness claim", gateway.calls.isEmpty());
        assertEquals(MonitorState.initial(), monitorState.value);
        assertTrue(monitor.queue.isEmpty());
    }

    @Test
    public void cacheOnlySegment_storageFailureIsReported() {
        readings.fail = true;

        Result<Segment> result = cachedSegment(NOW.minus(Duration.ofDays(7)), NOW);

        assertEquals(FetchOutcome.STORAGE_ERROR, result.outcome());
        assertEquals(DetailCode.STORAGE, result.detailCode());
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void hl4_yesterday_backfillComplete_noNetworkCall() {
        app.lightscout.domain.statistics.Period y = PeriodFactory.yesterday(NOW.atZone(ZoneOffset.UTC));
        readings.put(reading("y1", y.start().plus(Duration.ofHours(3)), 100),
                reading("y2", y.start().plus(Duration.ofHours(3)).plusSeconds(30), 101),   // DR-1 dedup (< 60 s)
                reading("t1", NOW.minus(Duration.ofHours(1)), 150));                      // today: outside the period
        Result<List<Reading>> r = period(y);
        assertEquals(List.of("y1"), r.value().stream().map(Reading::id).toList());
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void segmentContent_displayableReadings_markersWithoutTempBasal() {
        readings.put(reading("r1", NOW.minus(Duration.ofMinutes(30)), 100),
                reading("future", NOW.plus(Duration.ofMinutes(30)), 60));               // M-9: never drawn
        entries.put(tempBasal("tb", NOW.minus(Duration.ofMinutes(50)), 500, 10),
                mbg("bg", NOW.minus(Duration.ofMinutes(40)), 95),
                treatment("old", NOW.minus(Duration.ofHours(7)), EntryType.CARBS));     // before the window
        Segment s = segment(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1))).value();
        assertEquals(List.of("r1"), s.readings().stream().map(Reading::id).toList());
        assertEquals(List.of("bg"), s.entries().stream().map(Entry::id).toList());
        assertEquals(1, s.basal().size());
    }

    // ------------------------------------------------------------------ HL-2 / C-8 / R-21

    @Test
    public void hl2_rangeBeforeCompleteFrom_fetchedInSlices_upserted_completeFromLoweredOnMonitorExecutor() {
        Instant cf = NOW.minus(Duration.ofHours(24));
        completeFrom(cf, cf);
        gateway.readingsWindow = (f, t, l) -> page(true, List.of(reading("n" + f.getEpochSecond(), f.plusSeconds(60), 130)));
        gateway.entriesWindow = (f, t, l) -> page(true, List.of(treatment("t" + f.getEpochSecond(), f.plusSeconds(120), EntryType.CARBS)));
        Result<Segment> r = segment(NOW.minus(Duration.ofHours(72)), NOW);
        assertTrue(r.ok());
        // uncovered part [now − 72 h, now − 24 h) only, as two 24 h slices, newest first, per collection
        assertEquals(2, gateway.readingsWindowCalls.size());
        assertEquals(NOW.minus(Duration.ofHours(48)), gateway.readingsWindowCalls.get(0)[0]);
        assertEquals(cf, gateway.readingsWindowCalls.get(0)[1]);
        assertEquals(NOW.minus(Duration.ofHours(72)), gateway.readingsWindowCalls.get(1)[0]);
        assertEquals(2, gateway.meterBgWindowCalls.size());
        assertEquals(2, gateway.entriesWindowCalls.size());
        for (Instant[] c : gateway.readingsWindowCalls) {
            assertTrue(Duration.between(c[0], c[1]).compareTo(Duration.ofHours(24)) <= 0);
        }
        assertEquals(2, r.value().readings().size());    // served from the cache after upsert
        assertEquals(2, r.value().entries().size());
        // lowering is posted, not written on the io thread (single writer, R-21 / R-34)
        assertEquals(cf, cursorFrom(NsCollection.ENTRIES));
        monitor.runAll();
        assertEquals(NOW.minus(Duration.ofHours(72)), cursorFrom(NsCollection.ENTRIES));
        assertEquals(NOW.minus(Duration.ofHours(72)), cursorFrom(NsCollection.TREATMENTS));
        assertSame(monitorState.value, monitorMemory.get());
    }

    @Test
    public void hl2_c8_incompleteSlice_splitAgain_noDelete_completeFromLoweredOnlyOverCompleteSlices() {
        Instant cf = NOW.minus(Duration.ofHours(24));
        completeFrom(cf, cf);
        Instant olderSlice = NOW.minus(Duration.ofHours(72));
        Instant cached = olderSlice.plus(Duration.ofMinutes(10));
        readings.put(reading("keep", cached, 100));   // missing from the incomplete answer: must not be deleted
        gateway.readingsWindow = (f, t, l) -> f.isBefore(NOW.minus(Duration.ofHours(48)))
                ? page(false, List.of(reading("p" + f.getEpochSecond(), f, 120)))   // always returns `limit`
                : page(true, List.of());
        Result<Segment> r = segment(olderSlice, NOW);
        assertTrue(r.ok());
        assertTrue(gateway.readingsWindowCalls.size() > 2);   // the older slice was split again
        for (Instant[] c : readings.replaceWindowCalls) {
            assertFalse("no set difference over the incomplete slice", c[0].isBefore(NOW.minus(Duration.ofHours(48))));
        }
        assertTrue(readings.rows.containsKey("keep"));
        monitor.runAll();
        assertEquals(NOW.minus(Duration.ofHours(48)), cursorFrom(NsCollection.ENTRIES));   // newest complete slice only
    }

    @Test
    public void hl2_failureMidFetch_upsertsKept_failureReported_onlyCompleteNewerSliceLowers() {
        Instant cf = NOW.minus(Duration.ofHours(24));
        completeFrom(cf, cf);
        gateway.entriesWindow = (f, t, l) -> f.isBefore(NOW.minus(Duration.ofHours(48)))
                ? Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT)
                : page(true, List.of());
        gateway.readingsWindow = (f, t, l) -> page(true, List.of(reading("x" + f.getEpochSecond(), f, 120)));
        Result<Segment> r = segment(NOW.minus(Duration.ofHours(72)), NOW);
        assertEquals(FetchOutcome.NS_NOT_RESPONDING, r.outcome());
        assertEquals(2, readings.rows.size());   // both readings slices upserted
        monitor.runAll();
        assertEquals(NOW.minus(Duration.ofHours(48)), cursorFrom(NsCollection.ENTRIES));
        assertEquals(NOW.minus(Duration.ofHours(48)), cursorFrom(NsCollection.TREATMENTS));
    }

    @Test
    public void hl2_rangeNotAdjacentToCoverage_fetchedButCompleteFromUnchanged() {
        Instant cf = NOW.minus(Duration.ofHours(24));
        completeFrom(cf, cf);
        assertTrue(segment(NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(4))).ok());
        assertEquals(3, gateway.calls.size());
        assertTrue(monitor.queue.isEmpty());
        assertEquals(cf, cursorFrom(NsCollection.ENTRIES));
    }

    @Test
    public void r21_loweringSkippedWhenServerResetInBetween_neverBelowCacheWindow() {
        Instant cf = NOW.minus(Duration.ofHours(24));
        completeFrom(cf, cf);
        segment(NOW.minus(Duration.ofHours(48)), NOW);
        monitorState.value = MonitorState.initial();   // R-71 reset ran before the lowering task
        monitor.runAll();
        assertTrue(monitorState.puts.isEmpty());
        // a range older than the 31-day window lowers completeFrom to the window floor only
        completeFrom(NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(30)));
        segment(NOW.minus(Duration.ofDays(33)), NOW);
        monitor.runAll();
        assertEquals(NOW.minus(Duration.ofDays(31)), cursorFrom(NsCollection.ENTRIES));
    }

    @Test
    public void loadPeriod_uncovered_fetchesReadingsAndMeterBgOnly_lowersEntriesCursor() {
        Instant cf = NOW.minus(Duration.ofDays(3));
        completeFrom(cf, NOW.minus(Duration.ofHours(48)));
        Result<List<Reading>> r = period(PeriodFactory.lastDays(7, NOW));
        assertTrue(r.ok());
        assertEquals(4, gateway.readingsWindowCalls.size());
        assertEquals(4, gateway.meterBgWindowCalls.size());
        assertTrue(gateway.entriesWindowCalls.isEmpty());
        monitor.runAll();
        assertEquals(NOW.minus(Duration.ofDays(7)), cursorFrom(NsCollection.ENTRIES));
        assertEquals(NOW.minus(Duration.ofHours(48)), cursorFrom(NsCollection.TREATMENTS));
    }

    // ------------------------------------------------------------------ HL-3 / failures

    @Test
    public void hl3_uncoveredRangeOffline_offlineResult_noFetch() {
        completeFrom(NOW.minus(Duration.ofHours(24)), NOW.minus(Duration.ofHours(24)));
        connectivity.online = false;
        Result<Segment> r = segment(NOW.minus(Duration.ofHours(48)), NOW);
        assertEquals(FetchOutcome.NO_INTERNET, r.outcome());
        assertEquals(FetchOutcome.NO_INTERNET, period(PeriodFactory.lastDays(7, NOW)).outcome());
        assertTrue(gateway.calls.isEmpty());
        assertTrue(monitor.queue.isEmpty());
    }

    @Test
    public void unknownCoverage_firstFillNotDone_online_fetchesWholeRange_noLowering() {
        monitorState.value = MonitorState.initial();
        assertTrue(segment(NOW.minus(Duration.ofHours(6)), NOW).ok());
        assertEquals(3, gateway.calls.size());
        assertTrue(monitor.queue.isEmpty());
    }

    @Test
    public void storageFailures_storageErrorResult_cachedReadingsEmpty() {
        readings.fail = true;
        assertEquals(FetchOutcome.STORAGE_ERROR, segment(NOW.minus(Duration.ofHours(6)), NOW).outcome());
        assertEquals(DetailCode.STORAGE, segment(NOW.minus(Duration.ofHours(6)), NOW).detailCode());
        assertTrue(loader.cachedReadings(NOW.minus(Duration.ofHours(3)), NOW).isEmpty());
        readings.fail = false;
        readings.put(reading("r", NOW.minus(Duration.ofHours(1)), 100));
        assertEquals(1, loader.cachedReadings(NOW.minus(Duration.ofHours(3)), NOW).size());
    }

    // ------------------------------------------------------------------ R-26 basal band

    @Test
    public void r26_tempBasalWithProfileBaseline_fillsGapsFromProfile() {
        Instant from = NOW.minus(Duration.ofHours(2));   // 10:00 UTC
        entries.put(tempBasal("tb", from.plus(Duration.ofMinutes(30)), 500, 30));
        deviceStatus.profiles.add(new Profile("p", NOW.minus(Duration.ofDays(100)), ZoneOffset.UTC,
                List.of(new BasalStep(0, 1000), new BasalStep(41_400, 800))));   // 11:30 → 0.8 U/h
        List<BasalSegment> b = segment(from, NOW).value().basal();
        List<String> got = new ArrayList<>();
        for (BasalSegment s : b) {
            got.add(s.start() + "/" + s.duration().toMinutes() + "/" + s.rateMilliPerHour() + "/" + s.fromProfile());
        }
        assertEquals(List.of(
                "2026-10-15T10:00:00Z/30/1000/true",
                "2026-10-15T10:30:00Z/30/500/false",
                "2026-10-15T11:00:00Z/30/1000/true",
                "2026-10-15T11:30:00Z/30/800/true"), got);
    }

    @Test
    public void r26_noProfile_tempOnly_noTemp_empty_percentOnlyIgnored_laterTempCutsEarlier() {
        Instant from = NOW.minus(Duration.ofHours(2));
        deviceStatus.profiles.add(new Profile("p", from, ZoneOffset.UTC, List.of(new BasalStep(0, 1000))));
        assertTrue(segment(from, NOW).value().basal().isEmpty());   // profile alone never shows a band
        entries.put(new Entry("pct", EntryOrigin.TREATMENTS, EntryType.TEMP_BASAL, from, null, null, null, null, 30,
                null, "pump", "loop", "Temp Basal", false, 1L));                   // percent only: rate null
        assertTrue(segment(from, NOW).value().basal().isEmpty());
        deviceStatus.profiles.clear();
        entries.put(tempBasal("a", from.minus(Duration.ofMinutes(10)), 300, 60),   // started before the window
                tempBasal("b", from.plus(Duration.ofMinutes(20)), 0, 30));          // replaces "a"
        List<BasalSegment> b = segment(from, NOW).value().basal();
        assertEquals(2, b.size());
        assertEquals(from, b.get(0).start());
        assertEquals(Duration.ofMinutes(20), b.get(0).duration());
        assertEquals(300, b.get(0).rateMilliPerHour());
        assertEquals(0, b.get(1).rateMilliPerHour());
        assertFalse(b.get(1).fromProfile());
    }

    // ------------------------------------------------------------------ round 2 (IR-49, IR-51)

    @Test
    public void ir51_fromEqualsCompleteFrom_servedFromCache() {
        Instant cf = NOW.minus(Duration.ofHours(24));
        completeFrom(cf, cf);
        connectivity.online = false;
        assertTrue(segment(cf, NOW).ok());
        assertTrue(period(new app.lightscout.domain.statistics.Period(
                app.lightscout.domain.statistics.PeriodKind.LAST_24_HOURS, cf, NOW)).ok());
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void ir49_resetDuringFetch_oldServerDataDroppedAndResultDiscarded_noLowering() {
        Instant cf = NOW.minus(Duration.ofHours(24));
        completeFrom(cf, cf);
        gateway.readingsWindow = (f, t, l) -> {
            epoch.advance(() -> { });   // R-71 reset while the fetch is in flight
            return page(true, List.of(reading("old" + f.getEpochSecond(), f.plusSeconds(60), 130)));
        };
        Cp02bFakes.Recorder<Segment> recorder = new Cp02bFakes.Recorder<>();
        loader.loadSegment(NOW.minus(Duration.ofHours(48)), NOW, recorder);
        io.runAll();
        assertTrue("results from a server epoch that changed twice are discarded", recorder.results.isEmpty());
        assertTrue(readings.rows.isEmpty());
        assertTrue(readings.replaceWindowCalls.isEmpty());
        assertEquals(2, gateway.readingsWindowCalls.size());   // one bounded retry against the current epoch
        assertTrue(monitor.queue.isEmpty());
    }
}
