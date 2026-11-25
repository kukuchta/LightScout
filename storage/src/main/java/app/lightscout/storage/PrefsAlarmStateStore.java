package app.lightscout.storage;

import android.content.SharedPreferences;

import org.json.JSONException;

import java.time.DateTimeException;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.port.AlarmStateStore;

/**
 * IF-14 {@link AlarmStateStore}: ENT-08 (incl. {@code activeType} and the nullable {@code savedUserAlarmVolume})
 * in the ST-04 {@code runtime} preferences, written with {@code commit()} so that the saved volume is on disk
 * before the first volume change (06 §11 F8). Callers write on {@code monitorExecutor} only (R-34, R-35).
 */
final class PrefsAlarmStateStore implements AlarmStateStore {

    /** ST-04 key of the JSON-encoded alarm state. */
    static final String KEY = "alarm_state";

    private static final String OP_GET = "alarm_state.get";
    private static final String OP_PUT = "alarm_state.put";
    private static final String OP_CLEAR = "alarm_state.clear";

    private final SharedPreferences prefs;
    private final StoreGuard guard;

    /**
     * @param prefs the CE {@code runtime} preferences
     * @param guard failure logging and mapping (R-20)
     */
    PrefsAlarmStateStore(SharedPreferences prefs, StoreGuard guard) {
        this.prefs = prefs;
        this.guard = guard;
    }

    /**
     * {@inheritDoc}
     *
     * @return the stored state, or {@link AlarmState#empty()} when none was stored or the stored value cannot be
     *         parsed (Q-31: logged as {@code STORAGE_ERROR:alarm_state.get}, not thrown, so IF-01 still runs)
     */
    @Override
    public AlarmState get() {
        String json;
        try {
            json = prefs.getString(KEY, null);
        } catch (ClassCastException e) {
            guard.fail(OP_GET, e);
            return AlarmState.empty();
        } catch (RuntimeException e) {
            throw guard.fail(OP_GET, e);
        }
        if (json == null) {
            return AlarmState.empty();
        }
        try {
            return StateJson.alarmState(json);
        } catch (JSONException | DateTimeException | ClassCastException e) {
            guard.fail(OP_GET, e);
            return AlarmState.empty();
        }
    }

    @Override
    public void put(AlarmState s) {
        boolean written;
        try {
            SharedPreferences.Editor editor = prefs.edit().putString(KEY, StateJson.alarmState(s));
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (JSONException | RuntimeException e) {
            throw guard.fail(OP_PUT, e);
        }
        if (!written) {
            throw guard.fail(OP_PUT, null);
        }
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
}
