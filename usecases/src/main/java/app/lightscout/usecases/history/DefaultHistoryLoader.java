package app.lightscout.usecases.history;

import app.lightscout.domain.entry.ReadingRules;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.statistics.Period;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.Segment;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.common.ServerEpoch;
import app.lightscout.usecases.common.SlicedWindowFetcher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * CMP-12 implementation of IF-22 (04 §8 coverage rule; 05 C-7, C-8; ADR-030, ADR-031; CP-02 §8.4;
 * CP-00A R-21, R-23, R-26, R-34). Work and callbacks on {@code ioExecutor}.
 *
 * <p><b>Coverage rule</b>: a range with {@code from ≥ completeFrom} is served from the cache, online or offline.
 * Otherwise the uncovered part {@code [from, min(to, completeFrom))} is fetched when online — in ≤ 24 h slices,
 * newest first, {@code limit} 1000, a slice returning {@code limit} split again (C-8) — and upserted (set difference
 * only for complete slices); offline → {@code NO_INTERNET} without a request. A chart segment needs readings, meter
 * BG and treatments ({@code ENTRIES} + {@code TREATMENTS} cursors), a statistics period readings and meter BG
 * ({@code ENTRIES}).
 *
 * <p>{@code completeFrom} is lowered only over complete slices that are contiguous with the covered range, by a
 * read-modify-write task posted to {@code monitorExecutor} (single writer of IF-13 — R-21, R-34), never below the
 * 31-day cache window (ADR-030). That task lowers a cursor only if it still covers the point the fetch was based on
 * (a server reset in between leaves it alone).
 */
public final class DefaultHistoryLoader implements HistoryLoader {

    private static final Duration CACHE_WINDOW = Duration.ofDays(31);
    /** Look-back for temp basals that started before the window and still run in it. */
    private static final Duration TEMP_BASAL_LOOKBACK = Duration.ofHours(24);
    private static final List<NsCollection> SEGMENT_COLLECTIONS = List.of(NsCollection.ENTRIES, NsCollection.TREATMENTS);
    private static final List<NsCollection> PERIOD_COLLECTIONS = List.of(NsCollection.ENTRIES);

    private final Executor ioExecutor;
    private final Executor monitorExecutor;
    private final NightscoutGateway gateway;
    private final ReadingStore readings;
    private final EntryStore entries;
    private final DeviceStatusStore deviceStatus;
    private final MonitorStateStore monitorState;
    private final MonitorStateMemory monitorMemory;
    private final Connectivity connectivity;
    private final Clock clock;
    private final ServerEpoch epoch;

    /**
     * Creates the loader.
     *
     * @param ioExecutor      executor for fetches, cache reads and callbacks
     * @param monitorExecutor the single monitor thread (IF-13 writes)
     * @param gateway         IF-07
     * @param readings        IF-08
     * @param entries         IF-09
     * @param deviceStatus    IF-10 (profile for the basal baseline)
     * @param monitorState    IF-13 ({@code completeFrom} cursors)
     * @param monitorMemory   in-memory ENT-07 shared with the cycle (updated by the lowering task)
     * @param connectivity    IF-19
     * @param clock           IF-15
     * @param epoch           server epoch shared with the R-71 reset (IR-49: fetched data of an old server is dropped)
     */
    public DefaultHistoryLoader(Executor ioExecutor, Executor monitorExecutor, NightscoutGateway gateway,
                                ReadingStore readings, EntryStore entries, DeviceStatusStore deviceStatus,
                                MonitorStateStore monitorState, MonitorStateMemory monitorMemory,
                                Connectivity connectivity, Clock clock, ServerEpoch epoch) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.monitorExecutor = Objects.requireNonNull(monitorExecutor, "monitorExecutor");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.readings = Objects.requireNonNull(readings, "readings");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.deviceStatus = Objects.requireNonNull(deviceStatus, "deviceStatus");
        this.monitorState = Objects.requireNonNull(monitorState, "monitorState");
        this.monitorMemory = Objects.requireNonNull(monitorMemory, "monitorMemory");
        this.connectivity = Objects.requireNonNull(connectivity, "connectivity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
    }

    @Override
    public long serverEpoch() {
        return epoch.current();
    }

    @Override
    public void loadSegment(Instant from, Instant to, Callback<Segment> cb) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> loadSegmentForCurrentServer(from, to, cb, 0));
    }

    private void loadSegmentForCurrentServer(Instant from, Instant to, Callback<Segment> cb, int retries) {
        long started = epoch.current();
        Result<Segment> result = doSegment(from, to);
        if (!epoch.runIfCurrent(started, () -> cb.onResult(result))) {
            if (retries == 0) {
                ioExecutor.execute(() -> loadSegmentForCurrentServer(from, to, cb, 1));
            }
        }
    }

    private Result<Segment> doSegment(Instant from, Instant to) {
        if (to.isAfter(from)) {
            Result<Void> f = ensureCovered(from, to, SEGMENT_COLLECTIONS);
            if (!f.ok()) {
                return Result.fail(f.outcome(), f.detailCode());
            }
        }
        return readSegment(from, to);
    }

    @Override
    public void loadCachedSegment(Instant from, Instant to, Callback<Segment> cb) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> loadCachedSegmentForCurrentServer(from, to, cb, 0));
    }

    private void loadCachedSegmentForCurrentServer(Instant from, Instant to, Callback<Segment> cb, int retries) {
        long started = epoch.current();
        Result<Segment> result = readSegment(from, to);
        if (!epoch.runIfCurrent(started, () -> cb.onResult(result))) {
            if (retries == 0) {
                ioExecutor.execute(() -> loadCachedSegmentForCurrentServer(from, to, cb, 1));
            }
        }
    }

    private Result<Segment> readSegment(Instant from, Instant to) {
        try {
            Instant now = clock.now();
            List<Reading> rs = to.isAfter(from) ? ReadingRules.displayable(readings.range(from, to), now) : List.of();
            List<Entry> around = to.isAfter(from) ? entries.range(from.minus(TEMP_BASAL_LOOKBACK), to) : List.of();
            List<Entry> markers = new ArrayList<>();
            boolean anyTemp = false;
            for (Entry e : around) {
                if (e.type() == EntryType.TEMP_BASAL) {
                    anyTemp |= e.basalRateMilli() != null;
                } else if (!e.time().isBefore(from)) {
                    markers.add(e);
                }
            }
            Profile profile = anyTemp ? deviceStatus.latestProfile().orElse(null) : null;
            return Result.ok(new Segment(from, to, rs, markers, BasalBand.compute(around, profile, from, to)));
        } catch (StorageException e) {
            return Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        }
    }

    @Override
    public void loadPeriod(Period p, Callback<List<Reading>> cb) {
        Objects.requireNonNull(p, "period");
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> cb.onResult(doPeriod(p)));
    }

    private Result<List<Reading>> doPeriod(Period p) {
        if (p.end().isAfter(p.start())) {
            Result<Void> f = ensureCovered(p.start(), p.end(), PERIOD_COLLECTIONS);
            if (!f.ok()) {
                return Result.fail(f.outcome(), f.detailCode());
            }
        }
        try {
            return Result.ok(ReadingRules.displayable(readings.range(p.start(), p.end()), clock.now()));
        } catch (StorageException e) {
            return Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        }
    }

    @Override
    public List<Reading> cachedReadings(Instant from, Instant to) {
        try {
            return ReadingRules.displayable(readings.range(from, to), clock.now());
        } catch (StorageException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ coverage rule

    /** Fetches the uncovered part of {@code [from, to)} when needed (04 §8); ok when served from the cache. */
    private Result<Void> ensureCovered(Instant from, Instant to, List<NsCollection> collections) {
        long started = epoch.current();
        Instant covered = coveredFrom(collections);
        if (covered != null && !from.isBefore(covered)) {
            return Result.ok(null);
        }
        if (!connectivity.isOnline()) {
            return Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        }
        Instant end = covered != null && covered.isBefore(to) ? covered : to;
        boolean contiguous = covered != null && end.equals(covered);
        Instant lowered = null;
        Result<Void> outcome = Result.ok(null);
        List<Instant[]> slices = new ArrayList<>(SlicedWindowFetcher.slices(from, end));
        Collections.reverse(slices);   // newest first: completeFrom moves down contiguously
        for (Instant[] s : slices) {
            SliceOutcome so = fetchSlice(started, s[0], s[1], collections.contains(NsCollection.TREATMENTS));
            if (so.dropped()) {
                return Result.ok(null);   // IR-49: a server reset happened; nothing of the old server is stored
            }
            if (contiguous && so.complete()) {
                lowered = s[0];
            } else {
                contiguous = false;
            }
            if (so.failure() != null) {
                outcome = so.failure();
                break;   // no in-call retry (C-4); upserts already stored are kept
            }
        }
        if (lowered != null) {
            Instant floor = clock.now().minus(CACHE_WINDOW);
            postLowering(collections, lowered.isBefore(floor) ? floor : lowered, covered);
        }
        return outcome;
    }

    /** Result of one ≤ 24 h slice over all needed collections. */
    private record SliceOutcome(boolean complete, Result<Void> failure, boolean dropped) {

        SliceOutcome(boolean complete, Result<Void> failure) {
            this(complete, failure, false);
        }
    }

    private static final SliceOutcome DROPPED = new SliceOutcome(false, null, true);

    private SliceOutcome fetchSlice(long started, Instant from, Instant to, boolean withTreatments) {
        int limit = SlicedWindowFetcher.WINDOW_LIMIT;
        boolean complete = true;
        try {
            SlicedWindowFetcher.Fetched<Reading> fr = SlicedWindowFetcher.fetch(gateway::readingsWindow, from, to, limit);
            if (!epoch.writeIfCurrent(started, () -> SlicedWindowFetcher.store(fr, readingSink()))) {
                return DROPPED;
            }
            if (fr.failed()) {
                return new SliceOutcome(false, Result.fail(fr.failure(), fr.failureDetail()));
            }
            complete &= fr.fullyComplete();
            SlicedWindowFetcher.Fetched<Entry> fm = SlicedWindowFetcher.fetch(gateway::meterBgWindow, from, to, limit);
            if (!epoch.writeIfCurrent(started, () -> SlicedWindowFetcher.store(fm, entrySink(EntryOrigin.ENTRIES_MBG)))) {
                return DROPPED;
            }
            if (fm.failed()) {
                return new SliceOutcome(false, Result.fail(fm.failure(), fm.failureDetail()));
            }
            complete &= fm.fullyComplete();
            if (withTreatments) {
                SlicedWindowFetcher.Fetched<Entry> ft = SlicedWindowFetcher.fetch(gateway::entriesWindow, from, to, limit);
                if (!epoch.writeIfCurrent(started, () -> SlicedWindowFetcher.store(ft, entrySink(EntryOrigin.TREATMENTS)))) {
                    return DROPPED;
                }
                if (ft.failed()) {
                    return new SliceOutcome(false, Result.fail(ft.failure(), ft.failureDetail()));
                }
                complete &= ft.fullyComplete();
            }
        } catch (StorageException e) {
            return new SliceOutcome(false, Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE));
        }
        return new SliceOutcome(complete, null);
    }

    /** Highest {@code completeFrom} of the collections, or {@code null} when one is unknown or IF-13 fails. */
    private Instant coveredFrom(List<NsCollection> collections) {
        MonitorState ms;
        try {
            ms = monitorState.get();
        } catch (StorageException e) {
            return null;
        }
        Instant covered = null;
        for (NsCollection c : collections) {
            CollectionCursor cur = ms.cursors().get(c);
            if (cur == null || cur.completeFrom() == null) {
                return null;
            }
            if (covered == null || cur.completeFrom().isAfter(covered)) {
                covered = cur.completeFrom();
            }
        }
        return covered;
    }

    /** R-21 / R-34: read-modify-write of IF-13 on {@code monitorExecutor} (put follows get in the same task). */
    private void postLowering(List<NsCollection> collections, Instant lowered, Instant basedOn) {
        monitorExecutor.execute(() -> {
            try {
                MonitorState ms = monitorState.get();
                Map<NsCollection, CollectionCursor> cursors = new HashMap<>(ms.cursors());
                boolean changed = false;
                for (NsCollection c : collections) {
                    CollectionCursor cur = cursors.get(c);
                    if (cur == null || cur.completeFrom() == null || cur.completeFrom().isAfter(basedOn)
                            || !lowered.isBefore(cur.completeFrom())) {
                        continue;
                    }
                    cursors.put(c, new CollectionCursor(cur.lastModifiedSeen(), cur.hotWindowFrom(),
                            cur.lastReconcile(), lowered, cur.historyCapable(), cur.historyCursorMs()));
                    changed = true;
                }
                if (changed) {
                    MonitorState next = new MonitorState(ms.monitoringSince(), ms.lastSuccessfulCheck(),
                            ms.lastCheckCompleted(), ms.lastOutcome(), ms.connectionProblem(), ms.lastReadingTime(),
                            ms.arrivalLagMedianSec(), ms.recentLagSec(), ms.lastDailyMaintenance(), cursors);
                    monitorState.put(next);
                    monitorMemory.set(next);
                }
            } catch (StorageException e) {
                // logged by the store (R-13); the range is simply fetched again next time
            }
        });
    }

    private SlicedWindowFetcher.WindowSink<Reading> readingSink() {
        return new SlicedWindowFetcher.WindowSink<>() {
            @Override
            public void upsert(List<Reading> items) {
                readings.upsert(items);
            }

            @Override
            public void replaceWindow(Instant from, Instant to, List<Reading> items) {
                readings.replaceWindow(from, to, items);
            }
        };
    }

    private SlicedWindowFetcher.WindowSink<Entry> entrySink(EntryOrigin origin) {
        return new SlicedWindowFetcher.WindowSink<>() {
            @Override
            public void upsert(List<Entry> items) {
                entries.upsert(items);
            }

            @Override
            public void replaceWindow(Instant from, Instant to, List<Entry> items) {
                entries.replaceWindow(origin, from, to, items);
            }
        };
    }
}
