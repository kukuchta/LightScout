package app.lightscout.usecases.sync;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.Settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable working state of one tick (monitor thread only). Collects the first network failure, store failures,
 * the readings fetched in this tick and the cursor drafts that become the new {@link MonitorState} in step 6.
 */
final class TickContext {

    final Instant now;
    final MonitorState prev;
    final Settings settings;
    final List<Reading> fetchedReadings = new ArrayList<>();
    /** Nightscout {@code srvModified} (epoch ms) of the readings that arrived as changes: when the server had them. */
    final Map<String, Long> srvModifiedByReading = new java.util.HashMap<>();
    FetchOutcome connectionProblem;
    Instant lastDailyMaintenance;
    boolean storageFailed;
    private FetchOutcome failure;
    private String failureDetail;
    private final Map<NsCollection, CursorDraft> cursors = new EnumMap<>(NsCollection.class);

    TickContext(Instant now, MonitorState prev, Settings settings) {
        this.now = now;
        this.prev = prev;
        this.settings = settings;
        this.connectionProblem = prev.connectionProblem();
        this.lastDailyMaintenance = prev.lastDailyMaintenance();
        for (NsCollection c : NsCollection.values()) {
            cursors.put(c, CursorDraft.from(prev.cursors().get(c)));
        }
    }

    /** Records the first network failure of the tick (C-5 outcome); later failures are ignored. */
    void fail(FetchOutcome outcome, String detail) {
        if (failure == null) {
            failure = outcome;
            failureDetail = detail;
        }
    }

    boolean networkFailed() {
        return failure != null;
    }

    /** Marks a store failure (R-20 → STORAGE_ERROR); returns {@code false} for convenient early returns. */
    boolean storageFailed(StorageException e) {
        storageFailed = true;
        return false;
    }

    /** Tick outcome: network failure first, then STORAGE_ERROR, else OK (05 §9.1). */
    FetchOutcome outcome() {
        if (failure != null) {
            return failure;
        }
        return storageFailed ? FetchOutcome.STORAGE_ERROR : FetchOutcome.OK;
    }

    /** Detail code for the SYNC_FAIL event. */
    String detail() {
        if (failure != null) {
            return failureDetail;
        }
        return storageFailed ? app.lightscout.domain.port.DetailCode.STORAGE : null;
    }

    CursorDraft cursor(NsCollection c) {
        return cursors.get(c);
    }

    /** Cursor of the collection as read at tick start. */
    CollectionCursor prevCursor(NsCollection c) {
        return prev.cursors().get(c);
    }

    Map<NsCollection, CollectionCursor> cursorsOut() {
        Map<NsCollection, CollectionCursor> out = new EnumMap<>(NsCollection.class);
        for (Map.Entry<NsCollection, CursorDraft> e : cursors.entrySet()) {
            if (!e.getValue().isEmpty() || prev.cursors().containsKey(e.getKey())) {
                out.put(e.getKey(), e.getValue().toCursor());
            }
        }
        return out;
    }

    /** Mutable copy of a {@link CollectionCursor}. */
    static final class CursorDraft {
        Long lastModifiedSeen;
        Instant hotWindowFrom;
        Instant lastReconcile;
        Instant completeFrom;
        boolean historyCapable;
        Long historyCursorMs;

        static CursorDraft from(CollectionCursor c) {
            CursorDraft d = new CursorDraft();
            if (c != null) {
                d.lastModifiedSeen = c.lastModifiedSeen();
                d.hotWindowFrom = c.hotWindowFrom();
                d.lastReconcile = c.lastReconcile();
                d.completeFrom = c.completeFrom();
                d.historyCapable = c.historyCapable();
                d.historyCursorMs = c.historyCursorMs();
            }
            return d;
        }

        boolean isEmpty() {
            return lastModifiedSeen == null && hotWindowFrom == null && lastReconcile == null
                    && completeFrom == null && !historyCapable && historyCursorMs == null;
        }

        CollectionCursor toCursor() {
            return new CollectionCursor(lastModifiedSeen, hotWindowFrom, lastReconcile, completeFrom,
                    historyCapable, historyCursorMs);
        }
    }
}
