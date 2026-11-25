package app.lightscout.storage;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.lightscout.domain.port.NotifiedAlertStore;

/**
 * IF-64 {@link NotifiedAlertStore}: ENT-10, the set of pump-alert entry ids already notified, in the ST-04
 * {@code runtime} preferences. The set is <b>one JSON value</b> ({@code {"<entry id>": <entry time ms>}}) under
 * the constant key {@value #KEY}. Nightscout-derived ids are never used as preference keys (SR-30 / M-12,
 * IR-15). {@link #evictBefore(Instant)} trims the set to the cache window (CP-00A R-22, R-65). Written with
 * {@code commit()} on {@code monitorExecutor}.
 *
 * <p>A stored value that cannot be parsed reads as the empty set and is logged as {@code STORAGE_ERROR} (Q-31).
 * The next write replaces it. Real read and commit failures throw {@code StorageException} (R-20).
 */
final class PrefsNotifiedAlertStore implements NotifiedAlertStore {

    /** ST-04 key of the JSON-encoded set. */
    static final String KEY = "notified_alerts";

    private static final String OP_CONTAINS = "notified_alert.contains";
    private static final String OP_ADD = "notified_alert.add";
    private static final String OP_EVICT = "notified_alert.evictBefore";
    private static final String OP_CLEAR = "notified_alert.clear";

    private final SharedPreferences prefs;
    private final StoreGuard guard;

    /**
     * @param prefs the CE {@code runtime} preferences
     * @param guard failure logging and mapping (R-20)
     */
    PrefsNotifiedAlertStore(SharedPreferences prefs, StoreGuard guard) {
        this.prefs = prefs;
        this.guard = guard;
    }

    @Override
    public boolean contains(String entryId) {
        return read(OP_CONTAINS).has(entryId);
    }

    @Override
    public void add(String entryId, Instant entryTime) {
        JSONObject set = read(OP_ADD);
        try {
            set.put(entryId, entryTime.toEpochMilli());
        } catch (JSONException e) {
            throw guard.fail(OP_ADD, e);
        }
        write(set, OP_ADD);
    }

    @Override
    public void evictBefore(Instant t) {
        JSONObject set = read(OP_EVICT);
        long limit = t.toEpochMilli();
        List<String> stale = new ArrayList<>();
        for (Iterator<String> ids = set.keys(); ids.hasNext(); ) {
            String id = ids.next();
            if (set.optLong(id, Long.MIN_VALUE) < limit) {
                stale.add(id);
            }
        }
        for (String id : stale) {
            set.remove(id);
        }
        write(set, OP_EVICT);
    }

    @Override
    public void clear() {
        boolean written;
        try {
            written = StoreGuard.commit(prefs.edit().remove(KEY));
        } catch (RuntimeException e) {
            throw guard.fail(OP_CLEAR, e);
        }
        if (!written) {
            throw guard.fail(OP_CLEAR, null);
        }
    }

    private JSONObject read(String op) {
        String json;
        try {
            json = prefs.getString(KEY, null);
        } catch (ClassCastException e) {
            guard.fail(op, e);
            return new JSONObject();
        } catch (RuntimeException e) {
            throw guard.fail(op, e);
        }
        if (json == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            guard.fail(op, e);
            return new JSONObject();
        }
    }

    private void write(JSONObject set, String op) {
        boolean written;
        try {
            SharedPreferences.Editor editor = prefs.edit().putString(KEY, set.toString());
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(op, e);
        }
        if (!written) {
            throw guard.fail(op, null);
        }
    }
}
