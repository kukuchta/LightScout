package app.lightscout.feature.entry;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.WriteKind;
import app.lightscout.usecases.WriteResult;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Hand-written device fakes for TST-06 S-02 (no Mockito — CP-00 §2; no network — CP-00 §8). */
final class DeviceFakes {

    private DeviceFakes() {
    }

    /** IF-15 on the device clock and zone. */
    static final class DeviceClock implements Clock {

        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public long elapsedRealtimeMs() {
            return android.os.SystemClock.elapsedRealtime();
        }

        @Override
        public ZoneId zone() {
            return ZoneId.systemDefault();
        }
    }

    /**
     * IF-23 fake. Results are answered at once (on the calling thread) when a canned result is set; {@code null}
     * holds the call („in flight”) until the test completes it through the recorded callback.
     */
    static final class FakeWriter implements EntryWriter {

        final AtomicInteger newDrafts = new AtomicInteger();
        final AtomicInteger saveCalls = new AtomicInteger();
        final AtomicInteger deleteCalls = new AtomicInteger();
        final AtomicInteger loadCalls = new AtomicInteger();
        final List<EntryDraft> saved = new CopyOnWriteArrayList<>();
        final List<Callback<WriteResult>> pendingSaves = new CopyOnWriteArrayList<>();
        final List<Callback<WriteResult>> pendingDeletes = new CopyOnWriteArrayList<>();
        final Map<String, EntryDraft> editable = new ConcurrentHashMap<>();
        volatile Result<WriteResult> saveResult;
        volatile Result<WriteResult> deleteResult;
        volatile List<Entry> similar = Collections.emptyList();

        @Override
        public EntryDraft newDraft() {
            return new EntryDraft("draft-" + newDrafts.incrementAndGet(), EntryMode.CREATE, null, null, null, null);
        }

        @Override
        public void loadForEdit(String identifier, Callback<EntryDraft> cb) {
            loadCalls.incrementAndGet();
            EntryDraft d = editable.get(identifier);
            cb.onResult(d != null ? Result.ok(d) : Result.fail(FetchOutcome.OK, "GONE_404_410"));
        }

        @Override
        public void similarEntries(EntryDraft d, Callback<List<Entry>> cb) {
            cb.onResult(Result.ok(similar));
        }

        @Override
        public void save(EntryDraft d, Callback<WriteResult> cb) {
            saveCalls.incrementAndGet();
            saved.add(d);
            Result<WriteResult> r = saveResult;
            if (r == null) {
                pendingSaves.add(cb);
            } else {
                cb.onResult(r);
            }
        }

        @Override
        public void delete(String identifier, Callback<WriteResult> cb) {
            deleteCalls.incrementAndGet();
            Result<WriteResult> r = deleteResult;
            if (r == null) {
                pendingDeletes.add(cb);
            } else {
                cb.onResult(r);
            }
        }

        /** An editable entry for edit mode. */
        void putEditable(String id, EntryType type, Integer value, Instant time) {
            editable.put(id, new EntryDraft(id, EntryMode.EDIT, type, value, time, time.toEpochMilli()));
        }

        static Result<WriteResult> ok(WriteKind kind, String id) {
            return Result.ok(new WriteResult(kind, id));
        }
    }

    /** IF-21 with a switchable {@code online} flag. */
    static final class FakeStatusRepository implements StatusRepository {

        final List<StatusListener> listeners = new CopyOnWriteArrayList<>();
        volatile boolean online = true;

        @Override
        public StatusSnapshot current() {
            return snapshot(online);
        }

        @Override
        public void addListener(StatusListener l) {
            listeners.add(l);
        }

        @Override
        public void removeListener(StatusListener l) {
            listeners.remove(l);
        }

        @Override
        public void refresh() {
            // not used
        }

        @Override
        public void retry() {
            // not used
        }

        void setOnline(boolean value) {
            online = value;
            for (StatusListener l : listeners) {
                l.onStatus(snapshot(value));
            }
        }

        static StatusSnapshot snapshot(boolean online) {
            ConsumableStatus site = new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND,
                    null, null, null, null);
            ConsumableStatus sensor = new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND,
                    null, null, null, null);
            AlarmType ringing = null;
            return new StatusSnapshot(null, null, null, false, new DataLossStatus(DataLossKind.NONE, null, null),
                    FetchOutcome.OK, null, null, null, online, false, false, true, true, true, 55, 70, 250, 20,
                    ringing, null, site, sensor, 0, Banner.NONE, Instant.EPOCH);
        }
    }
}
