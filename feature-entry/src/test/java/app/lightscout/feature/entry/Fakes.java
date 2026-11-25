package app.lightscout.feature.entry;

import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Result;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.WriteResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Hand-written fakes for the S-02 JVM tests (no Mockito — CP-00 §2). */
final class Fakes {

    /** Runs tasks immediately on the calling thread. */
    static final Executor DIRECT = Runnable::run;

    private Fakes() {
    }

    /** IF-15 with a settable time; zone UTC. */
    static final class FixedClock implements Clock {

        Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return now.toEpochMilli();
        }

        @Override
        public ZoneId zone() {
            return ZoneOffset.UTC;
        }
    }

    /** In-memory {@link StateStore}; the map survives a „process death” (a new model on the same store). */
    static final class MapStore implements StateStore {

        final Map<String, Object> map = new HashMap<>();

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public String getString(String key) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : null;
        }

        @Override
        public void putString(String key, String value) {
            map.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : fallback;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            map.put(key, value);
        }
    }

    /** One recorded IF-23 call with its pending callback. */
    static final class Call<A, T> {

        final A arg;
        final Callback<T> cb;

        Call(A arg, Callback<T> cb) {
            this.arg = arg;
            this.cb = cb;
        }

        void complete(Result<T> r) {
            cb.onResult(r);
        }
    }

    /** IF-23 fake: records every call; callbacks are completed by the test. */
    static final class FakeWriter implements EntryWriter {

        int newDrafts;
        final List<Call<String, EntryDraft>> loads = new ArrayList<>();
        final List<Call<EntryDraft, List<Entry>>> similar = new ArrayList<>();
        final List<Call<EntryDraft, WriteResult>> saves = new ArrayList<>();
        final List<Call<String, WriteResult>> deletes = new ArrayList<>();

        @Override
        public EntryDraft newDraft() {
            newDrafts++;
            return new EntryDraft("draft-" + newDrafts, EntryMode.CREATE, null, null, null, null);
        }

        @Override
        public void loadForEdit(String identifier, Callback<EntryDraft> cb) {
            loads.add(new Call<>(identifier, cb));
        }

        @Override
        public void similarEntries(EntryDraft d, Callback<List<Entry>> cb) {
            similar.add(new Call<>(d, cb));
        }

        @Override
        public void save(EntryDraft d, Callback<WriteResult> cb) {
            saves.add(new Call<>(d, cb));
        }

        @Override
        public void delete(String identifier, Callback<WriteResult> cb) {
            deletes.add(new Call<>(identifier, cb));
        }

        Call<EntryDraft, List<Entry>> lastSimilar() {
            return similar.get(similar.size() - 1);
        }
    }

    /** Records state and events. */
    static final class Recorder implements EntryFormModel.Listener {

        final List<EntryFormUiState> states = new ArrayList<>();
        final List<EntryFormModel.Event> events = new ArrayList<>();

        @Override
        public void onState(EntryFormUiState s) {
            states.add(s);
        }

        @Override
        public void onEvent(EntryFormModel.Event e) {
            events.add(e);
        }
    }

    static Entry entry(String id, EntryType type, Instant time, Integer carbs, String enteredBy, boolean own) {
        return new Entry(id, EntryOrigin.TREATMENTS, type, time, null, carbs, null, null, null, null, enteredBy,
                own ? "LightScout" : null, null, own, null);
    }
}
