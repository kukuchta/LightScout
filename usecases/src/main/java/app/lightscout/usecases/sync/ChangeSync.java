package app.lightscout.usecases.sync;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.usecases.common.SlicedWindowFetcher;
import app.lightscout.usecases.common.SlicedWindowFetcher.Fetched;
import app.lightscout.usecases.common.SlicedWindowFetcher.WindowSink;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

/**
 * WF-01 step 4: fetch the changed collections and store them (CP-02 §8.2 SY-1…SY-8, M-13, M-14, C-7, C-8;
 * CP-00A R-21, R-23, R-69). Also hosts the windowed fetches used by the SY-8 backfill and the SY-4 reconcile.
 *
 * <p>Rules: the first network failure stops further calls of the tick (C-4); data received before it is upserted
 * without deletes; set-difference deletes only for complete slices (C-8); a cursor advances only after its
 * documents were stored (R-21).
 */
final class ChangeSync {

    /** SY-2 overlap. */
    static final Duration OVERLAP = Duration.ofMinutes(10);
    /** M-9 / M-14 future tolerance. */
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(10);
    /** SY-3 hot window. */
    static final Duration HOT_WINDOW = Duration.ofHours(48);
    /** SY-8 initial fill. */
    static final Duration INITIAL_FILL = Duration.ofHours(24);
    /** SY-5 devicestatus window. */
    static final Duration DEVICESTATUS_WINDOW = Duration.ofHours(24);
    /** Cache window (ADR-030, D-016). */
    static final Duration CACHE_WINDOW = Duration.ofDays(31);
    /** IF-34 limit (C-7). */
    static final int ENTRIES_LIMIT = 1000;
    /** IF-37 limit (C-7). */
    static final int HISTORY_LIMIT = 500;
    /** Guard against a server that never reports {@code complete}. */
    static final int MAX_PAGES = 50;
    /**
     * BUG-18: after a backfill slice that ended without a failure but still incomplete (window not honoured, or
     * too dense to complete), the collection's backfill pauses for this long (in memory, per process) instead of
     * re-fetching the same slice on every tick.
     */
    static final Duration BACKFILL_PAUSE = Duration.ofHours(24);
    /** DCR-45 (D-165): time one tick may spend on the SY-8 backfill; the rest continues on the next OK tick. */
    static final Duration BACKFILL_BUDGET = Duration.ofSeconds(15);
    /** Logcat tag of the debug-only backfill line (no values — SR-07). */
    static final String BACKFILL_TAG = "SyncBackfill";

    private final NightscoutGateway gateway;
    private final ReadingStore readings;
    private final EntryStore entries;
    private final DeviceStatusStore deviceStatus;
    private final Logger logger;
    /** BUG-18: backfill pause per collection (monitor thread only). */
    private final Map<NsCollection, Pause> backfillPaused = new EnumMap<>(NsCollection.class);

    /**
     * A backfill pause. It only applies while the stuck slice is still the next one: same {@code completeFrom} and
     * same {@code monitoringSince} (S-B F4: a cursor reset — new server identity, cleared monitor state — or a
     * {@code completeFrom} lowered elsewhere ends it).
     */
    private record Pause(Instant until, Instant completeFrom, Instant monitoringSince) {

        boolean holds(Instant now, Instant completeFrom, Instant monitoringSince) {
            return now.isBefore(until) && completeFrom.equals(this.completeFrom)
                    && java.util.Objects.equals(monitoringSince, this.monitoringSince);
        }
    }

    ChangeSync(NightscoutGateway gateway, ReadingStore readings, EntryStore entries, DeviceStatusStore deviceStatus,
               Logger logger) {
        this.gateway = gateway;
        this.readings = readings;
        this.entries = entries;
        this.deviceStatus = deviceStatus;
        this.logger = logger;
    }

    /**
     * SY-1 gate: fetch when {@code lastModified} moved since the stored value, or when it is more than 10 min in
     * the future (M-14). A collection the server does not report is not fetched.
     */
    static boolean shouldFetch(Long lastModified, Long seen, Instant now) {
        if (lastModified == null) {
            return false;
        }
        if (lastModified > now.plus(FUTURE_TOLERANCE).toEpochMilli()) {
            return true;
        }
        return !lastModified.equals(seen);
    }

    /** Fetches and stores every changed collection (SY-1…SY-7). */
    void fetchChanged(TickContext t, Map<NsCollection, Long> lastModified) {
        for (NsCollection c : NsCollection.values()) {
            if (t.networkFailed()) {
                return;
            }
            Long lm = lastModified.get(c);
            TickContext.CursorDraft cur = t.cursor(c);
            if (!shouldFetch(lm, cur.lastModifiedSeen, t.now)) {
                continue;
            }
            boolean done = switch (c) {
                case ENTRIES -> cur.historyCapable ? history(t, c) : entriesIncremental(t);
                case TREATMENTS -> cur.historyCapable ? history(t, c) : treatmentsHotWindow(t);
                case DEVICESTATUS -> deviceStatus(t);
                case PROFILE -> profile(t);
            };
            if (done) {
                cur.lastModifiedSeen = lm;
            }
        }
    }

    /** SY-2 / SY-8: readings and meter BG since {@code lastReadingTime − 10 min}, or the last 24 h when none. */
    private boolean entriesIncremental(TickContext t) {
        Instant last = t.prev.lastReadingTime();
        Instant from = last != null ? last.minus(OVERLAP) : t.now.minus(INITIAL_FILL);
        long fromMs = from.toEpochMilli();
        boolean ok = pageSince(ms -> gateway.readingsSince(ms, ENTRIES_LIMIT), fromMs, Reading::time,
                readings::upsert, t.fetchedReadings::addAll, t);
        if (!ok) {
            return false;
        }
        ok = pageSince(ms -> gateway.meterBgSince(ms, ENTRIES_LIMIT), fromMs, Entry::time,
                entries::upsert, list -> { }, t);
        if (!ok) {
            return false;
        }
        TickContext.CursorDraft cur = t.cursor(NsCollection.ENTRIES);
        if (cur.completeFrom == null) {
            cur.completeFrom = from;
        }
        return true;
    }

    /**
     * IF-34 paging: continue from the newest document time of an incomplete page (the gateway returns the oldest
     * {@code limit} documents after the bound — assumption A-3). Every page is upserted as it arrives.
     */
    private <T> boolean pageSince(LongFunction<Result<Page<T>>> call, long fromMs, Function<T, Instant> timeOf,
                                  Consumer<List<T>> store, Consumer<List<T>> onFetched, TickContext t) {
        long cursor = fromMs;
        for (int i = 0; i < MAX_PAGES; i++) {
            Result<Page<T>> r = call.apply(cursor);
            if (!r.ok()) {
                t.fail(r.outcome(), r.detailCode());
                return false;
            }
            Page<T> page = r.value();
            List<T> items = page == null ? List.of() : page.items();
            onFetched.accept(items);
            if (!items.isEmpty()) {
                try {
                    store.accept(items);
                } catch (StorageException e) {
                    return t.storageFailed(e);
                }
            }
            if (page == null || page.complete()) {
                return true;
            }
            long max = cursor;
            for (T item : items) {
                Instant time = timeOf.apply(item);
                if (time != null && time.toEpochMilli() > max) {
                    max = time.toEpochMilli();
                }
            }
            if (max <= cursor) {
                return true;
            }
            cursor = max;
        }
        return true;
    }

    /** SY-3: hot window {@code [now − 48 h, now + 10 min)} in ≤ 24 h slices with set difference on complete ones. */
    private boolean treatmentsHotWindow(TickContext t) {
        Instant from = t.now.minus(HOT_WINDOW);
        Instant to = t.now.plus(FUTURE_TOLERANCE);
        Fetched<Entry> f = SlicedWindowFetcher.fetch(gateway::entriesWindow, from, to, SlicedWindowFetcher.WINDOW_LIMIT);
        if (f.failed()) {
            t.fail(f.failure(), f.failureDetail());
        }
        if (!store(f, entrySink(EntryOrigin.TREATMENTS), t) || f.failed()) {
            return false;
        }
        TickContext.CursorDraft cur = t.cursor(NsCollection.TREATMENTS);
        cur.hotWindowFrom = from;
        if (cur.completeFrom == null && f.fullyComplete()) {
            cur.completeFrom = from;
        }
        return true;
    }

    /** SY-5: device status of the last 24 h. */
    private boolean deviceStatus(TickContext t) {
        Result<List<DeviceStatus>> r = gateway.deviceStatusSince(t.now.minus(DEVICESTATUS_WINDOW));
        if (!r.ok()) {
            t.fail(r.outcome(), r.detailCode());
            return false;
        }
        List<DeviceStatus> list = r.value() == null ? List.of() : r.value();
        if (!list.isEmpty()) {
            try {
                deviceStatus.upsert(list);
            } catch (StorageException e) {
                return t.storageFailed(e);
            }
        }
        return true;
    }

    /** SY-6: latest profile; ok(null) = no basal baseline (Q-35) → nothing stored. */
    private boolean profile(TickContext t) {
        Result<Profile> r = gateway.latestProfile();
        if (!r.ok()) {
            t.fail(r.outcome(), r.detailCode());
            return false;
        }
        if (r.value() != null) {
            try {
                deviceStatus.saveProfile(r.value());
            } catch (StorageException e) {
                return t.storageFailed(e);
            }
        }
        return true;
    }

    /** SY-7: history mode — page IF-37 by {@code srvModified}, applying {@code isValid:false} deletions. */
    private boolean history(TickContext t, NsCollection c) {
        TickContext.CursorDraft cur = t.cursor(c);
        long cursor = cur.historyCursorMs != null ? cur.historyCursorMs
                : cur.lastModifiedSeen != null ? cur.lastModifiedSeen
                : t.now.minus(HOT_WINDOW).toEpochMilli();
        long maxSeen = cursor;
        for (int i = 0; i < MAX_PAGES; i++) {
            Result<Page<Change>> r = gateway.history(c, cursor, HISTORY_LIMIT);
            if (!r.ok()) {
                t.fail(r.outcome(), r.detailCode());
                return false;
            }
            Page<Change> page = r.value();
            List<Change> items = page == null ? List.of() : page.items();
            try {
                applyHistory(c, items, t);
            } catch (StorageException e) {
                return t.storageFailed(e);
            }
            long pageMax = cursor;
            for (Change ch : items) {
                pageMax = Math.max(pageMax, ch.srvModifiedMs());
            }
            maxSeen = Math.max(maxSeen, pageMax);
            if (page == null || page.complete() || pageMax <= cursor) {
                break;
            }
            cursor = pageMax;
        }
        cur.historyCursorMs = maxSeen;
        return true;
    }

    private void applyHistory(NsCollection c, List<Change> changes, TickContext t) {
        if (c == NsCollection.ENTRIES) {
            List<Reading> rs = new ArrayList<>();
            List<Entry> mbg = new ArrayList<>();
            for (Change ch : changes) {
                if (ch.deleted()) {
                    // A deleted reading cannot be removed by id (IF-08 has no delete) — SY-4 removes it (Q-new-1).
                    entries.delete(ch.id());
                } else if (ch.reading() != null) {
                    rs.add(ch.reading());
                    t.srvModifiedByReading.put(ch.reading().id(), ch.srvModifiedMs());
                } else if (ch.entry() != null) {
                    mbg.add(ch.entry());
                }
            }
            t.fetchedReadings.addAll(rs);
            if (!rs.isEmpty()) {
                readings.upsert(rs);
            }
            if (!mbg.isEmpty()) {
                entries.upsert(mbg);
            }
            return;
        }
        // TREATMENTS: a change for base id X replaces every stored form (X, X#carbs, X#insulin); a deletion
        // removes all of them (CP-03 merge note).
        Map<String, List<Entry>> byBase = new LinkedHashMap<>();
        for (Change ch : changes) {
            String base = IdForms.base(ch.id());
            List<Entry> pending = byBase.computeIfAbsent(base, k -> new ArrayList<>());
            if (ch.deleted()) {
                pending.clear();
            } else if (ch.entry() != null) {
                pending.add(ch.entry());
            }
        }
        List<Entry> upserts = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> e : byBase.entrySet()) {
            for (String form : IdForms.forms(e.getKey())) {
                entries.delete(form);
            }
            upserts.addAll(e.getValue());
        }
        if (!upserts.isEmpty()) {
            entries.upsert(upserts);
        }
    }

    /**
     * SY-8 backfill (R-69 (3), DCR-45): 24 h slices, newest first, down to the cache window, within a time budget
     * of {@link #BACKFILL_BUDGET} per tick (measured from the first slice on {@code elapsedMs}). The collections
     * take turns slice by slice, so treatments (site / sensor changes) are not starved by a long readings
     * history. Each complete slice lowers {@code completeFrom} (the draft, so the tick that did the initial fill
     * continues at once). The budget is checked between slices: the last slice may overrun it by its own
     * duration (a slice is capped by {@link SlicedWindowFetcher#MAX_CALLS_PER_SLICE}). What does not fit
     * continues on the next OK tick. A failure here does not change the tick outcome and ends the backfill of the
     * tick. A slice that ends incomplete without a failure pauses that collection's backfill for
     * {@link #BACKFILL_PAUSE} (BUG-18).
     */
    void backfill(TickContext t, LongSupplier elapsedMs) {
        Instant floor = t.now.minus(CACHE_WINDOW);
        long deadline = elapsedMs.getAsLong() + BACKFILL_BUDGET.toMillis();
        List<NsCollection> active = new ArrayList<>(List.of(NsCollection.ENTRIES, NsCollection.TREATMENTS));
        while (!active.isEmpty()) {
            for (java.util.Iterator<NsCollection> it = active.iterator(); it.hasNext(); ) {
                if (elapsedMs.getAsLong() >= deadline) {
                    logger.debug(BACKFILL_TAG, "budget spent");
                    return;
                }
                switch (backfillSlice(t, it.next(), floor)) {
                    case MORE -> { }
                    case DONE -> it.remove();
                    case STOP -> {
                        return;
                    }
                }
            }
        }
    }

    private enum SliceStep { MORE, DONE, STOP }

    /** One backfill slice of {@code c}: {@code MORE} when another slice is due, {@code STOP} on a failure. */
    private SliceStep backfillSlice(TickContext t, NsCollection c, Instant floor) {
        TickContext.CursorDraft cur = t.cursor(c);
        Instant prevFrom = cur.completeFrom;
        if (prevFrom == null || !prevFrom.isAfter(floor)) {
            return SliceStep.DONE;
        }
        Pause pause = backfillPaused.get(c);
        if (pause != null) {
            if (pause.holds(t.now, prevFrom, t.prev.monitoringSince())) {
                return SliceStep.DONE;
            }
            backfillPaused.remove(c);
        }
        Instant from = prevFrom.minus(SlicedWindowFetcher.MAX_SLICE);
        if (from.isBefore(floor)) {
            from = floor;
        }
        WindowResult w = c == NsCollection.ENTRIES ? entriesWindow(t, from, prevFrom)
                : treatmentsWindow(t, from, prevFrom);
        if (w.failure() != null) {
            return SliceStep.STOP;
        }
        if (w.complete()) {
            cur.completeFrom = from;
            return SliceStep.MORE;
        }
        if (w.stuck()) {
            backfillPaused.put(c, new Pause(t.now.plus(BACKFILL_PAUSE), prevFrom, t.prev.monitoringSince()));
            logger.debug(BACKFILL_TAG, "paused " + c.name() + (w.windowIgnored() ? " window-ignored" : " incomplete"));
        }
        return SliceStep.DONE;
    }

    /**
     * Result of a windowed fetch + store outside the tick outcome (backfill, reconcile).
     *
     * @param complete      every slice complete and stored
     * @param failure       first network failure; nullable
     * @param stuck         no failure, everything stored, yet incomplete: a retry would fetch the same (BUG-18)
     * @param windowIgnored the server did not honour the window (BUG-18)
     */
    record WindowResult(boolean complete, FetchOutcome failure, boolean stuck, boolean windowIgnored) {
    }

    /** Readings + meter BG of {@code [from, to)} with set difference on complete slices (SY-4, SY-8). */
    WindowResult entriesWindow(TickContext t, Instant from, Instant to) {
        Fetched<Reading> fr = SlicedWindowFetcher.fetch(gateway::readingsWindow, from, to,
                SlicedWindowFetcher.WINDOW_LIMIT);
        // BUG-18: a server that ignored the readings window ignores the meter BG one too — not asked
        Fetched<Entry> fm = fr.failed() || fr.windowIgnored() ? null : SlicedWindowFetcher.fetch(
                gateway::meterBgWindow, from, to, SlicedWindowFetcher.WINDOW_LIMIT);
        boolean stored = store(fr, readingSink(), t) && (fm == null || store(fm, entrySink(EntryOrigin.ENTRIES_MBG), t));
        FetchOutcome failure = fr.failed() ? fr.failure() : fm != null && fm.failed() ? fm.failure() : null;
        boolean complete = failure == null && stored && fm != null && fr.fullyComplete() && fm.fullyComplete();
        boolean ignored = fr.windowIgnored() || (fm != null && fm.windowIgnored());
        return new WindowResult(complete, failure, failure == null && stored && !complete, ignored);
    }

    /** Treatments of {@code [from, to)} with set difference on complete slices (SY-4, SY-8). */
    WindowResult treatmentsWindow(TickContext t, Instant from, Instant to) {
        Fetched<Entry> f = SlicedWindowFetcher.fetch(gateway::entriesWindow, from, to, SlicedWindowFetcher.WINDOW_LIMIT);
        boolean stored = store(f, entrySink(EntryOrigin.TREATMENTS), t);
        boolean complete = !f.failed() && stored && f.fullyComplete();
        return new WindowResult(complete, f.failure(), !f.failed() && stored && !complete, f.windowIgnored());
    }

    private <T> boolean store(Fetched<T> f, WindowSink<T> sink, TickContext t) {
        try {
            SlicedWindowFetcher.store(f, sink);
            return true;
        } catch (StorageException e) {
            return t.storageFailed(e);
        }
    }

    private WindowSink<Reading> readingSink() {
        return new WindowSink<>() {
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

    private WindowSink<Entry> entrySink(EntryOrigin origin) {
        return new WindowSink<>() {
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
