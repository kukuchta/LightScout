package app.lightscout.storage;

import android.content.SharedPreferences;

import org.json.JSONException;

import java.time.DateTimeException;
import java.util.Map;

import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.MonitorStateStore;

/**
 * IF-13 {@link MonitorStateStore}: ENT-07 in the ST-04 {@code runtime} preferences, written with
 * {@code commit()} (callers write on {@code monitorExecutor} only — CP-00A R-21, R-34). Includes the
 * per-collection cursors with {@code completeFrom} and {@code historyCapable} (ADR-071).
 *
 * <p><b>Cache re-creation</b> (A-5 ruling): each {@link #put} also records the {@link CacheGeneration} that the
 * writing thread saw at its last {@link #get}. If {@code cache.db} was re-created since then, {@link #get}
 * returns the stored state with <b>empty cursors</b>, which forces a full refetch. All other fields are kept. The
 * reset is applied on read, so no thread other than the writer ever writes {@code MonitorState}.
 *
 * <p><b>Bad content</b> (Q-31): a stored value that cannot be parsed (JSON error, wrong preference type) is logged
 * as {@code STORAGE_ERROR:monitor_state.get}, and {@link MonitorState#initial()} is returned. The next
 * {@link #put} overwrites it. Real I/O failures still throw (R-20).
 */
final class PrefsMonitorStateStore implements MonitorStateStore {

    /** ST-04 key of the JSON-encoded monitor state. */
    static final String KEY = "monitor_state";

    /** ST-04 key of the {@link CacheGeneration} value the stored cursors belong to. */
    static final String CURSORS_GENERATION_KEY = "monitor_state_cache_generation";

    private static final String OP_GET = "monitor_state.get";
    private static final String OP_PUT = "monitor_state.put";
    private static final String OP_CLEAR = "monitor_state.clear";

    private final SharedPreferences prefs;
    private final CacheGeneration generation;
    private final StoreGuard guard;

    /** Cache generation seen by the last {@link #get} of the current thread (read-modify-write on one thread). */
    private final ThreadLocal<Long> generationAtGet = new ThreadLocal<>();

    /**
     * @param prefs      the CE {@code runtime} preferences
     * @param generation cache re-creation counter
     * @param guard      failure logging and mapping (R-20)
     */
    PrefsMonitorStateStore(SharedPreferences prefs, CacheGeneration generation, StoreGuard guard) {
        this.prefs = prefs;
        this.generation = generation;
        this.guard = guard;
    }

    /**
     * {@inheritDoc}
     *
     * @return the stored state (cursors emptied after a cache re-creation), or {@link MonitorState#initial()}
     *         when none was stored or the stored value cannot be parsed
     */
    @Override
    public MonitorState get() {
        String json;
        long current;
        long cursorsGeneration;
        try {
            current = generation.current();
            generationAtGet.set(current);
            json = prefs.getString(KEY, null);
            cursorsGeneration = prefs.getLong(CURSORS_GENERATION_KEY, 0L);
        } catch (ClassCastException e) {
            guard.fail(OP_GET, e);
            return MonitorState.initial();
        } catch (RuntimeException e) {
            throw guard.fail(OP_GET, e);
        }
        if (json == null) {
            return MonitorState.initial();
        }
        MonitorState stored;
        try {
            stored = StateJson.monitorState(json);
        } catch (JSONException | DateTimeException | ClassCastException e) {
            guard.fail(OP_GET, e);
            return MonitorState.initial();
        }
        if (cursorsGeneration == current) {
            return stored;
        }
        return new MonitorState(stored.monitoringSince(), stored.lastSuccessfulCheck(), stored.lastCheckCompleted(),
                stored.lastOutcome(), stored.connectionProblem(), stored.lastReadingTime(),
                stored.arrivalLagMedianSec(), stored.recentLagSec(), stored.lastDailyMaintenance(), Map.of());
    }

    @Override
    public void put(MonitorState s) {
        boolean written;
        try {
            // IR-16: the generation of the get() this put is based on. Without a get() on this thread, keep the
            // stored generation (or UNREADABLE if none), so that a pending reset is never lost.
            Long seen = generationAtGet.get();
            long cursorsGeneration = seen != null ? seen : storedCursorsGeneration();
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY, StateJson.monitorState(s))
                    .putLong(CURSORS_GENERATION_KEY, cursorsGeneration);
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (JSONException | RuntimeException e) {
            throw guard.fail(OP_PUT, e);
        } finally {
            generationAtGet.remove();
        }
        if (!written) {
            throw guard.fail(OP_PUT, null);
        }
    }

    private long storedCursorsGeneration() {
        try {
            return prefs.contains(CURSORS_GENERATION_KEY)
                    ? prefs.getLong(CURSORS_GENERATION_KEY, CacheGeneration.UNREADABLE)
                    : CacheGeneration.UNREADABLE;
        } catch (ClassCastException e) {
            return CacheGeneration.UNREADABLE;
        }
    }

    @Override
    public void clear() {
        boolean written;
        try {
            written = StoreGuard.commit(prefs.edit().remove(KEY).remove(CURSORS_GENERATION_KEY));
        } catch (RuntimeException e) {
            throw guard.fail(OP_CLEAR, e);
        }
        if (!written) {
            throw guard.fail(OP_CLEAR, null);
        }
    }
}
