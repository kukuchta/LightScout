package app.lightscout.storage;

import android.content.SharedPreferences;

import org.json.JSONException;

import java.time.DateTimeException;
import java.util.Optional;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.port.ReminderStateStore;

/**
 * IF-63 {@link ReminderStateStore}: ENT-09, one JSON value per consumable kind in the ST-04 {@code runtime}
 * preferences, written with {@code commit()} on {@code monitorExecutor} (CP-00A R-22).
 */
final class PrefsReminderStateStore implements ReminderStateStore {

    /** ST-04 key of the SITE reminder state (constant key, SR-30 / M-12). */
    static final String KEY_SITE = "reminder_state_SITE";
    /** ST-04 key of the SENSOR reminder state. */
    static final String KEY_SENSOR = "reminder_state_SENSOR";

    private static final String OP_GET = "reminder_state.get";
    private static final String OP_PUT = "reminder_state.put";
    private static final String OP_CLEAR = "reminder_state.clear";

    private final SharedPreferences prefs;
    private final StoreGuard guard;

    /**
     * @param prefs the CE {@code runtime} preferences
     * @param guard failure logging and mapping (R-20)
     */
    PrefsReminderStateStore(SharedPreferences prefs, StoreGuard guard) {
        this.prefs = prefs;
        this.guard = guard;
    }

    /**
     * {@inheritDoc}
     *
     * @return the stored state, or empty when none was stored or the stored value cannot be parsed (Q-31: logged
     *         as {@code STORAGE_ERROR:reminder_state.get}, not thrown)
     */
    @Override
    public Optional<ReminderState> get(ConsumableKind k) {
        String json;
        try {
            json = prefs.getString(keyFor(k), null);
        } catch (ClassCastException e) {
            guard.fail(OP_GET, e);
            return Optional.empty();
        } catch (RuntimeException e) {
            throw guard.fail(OP_GET, e);
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(StateJson.reminderState(k, json));
        } catch (JSONException | DateTimeException | ClassCastException | NullPointerException e) {
            guard.fail(OP_GET, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(ReminderState s) {
        boolean written;
        try {
            SharedPreferences.Editor editor = prefs.edit().putString(keyFor(s.kind()), StateJson.reminderState(s));
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
            SharedPreferences.Editor editor = prefs.edit();
            for (ConsumableKind kind : ConsumableKind.values()) {
                editor.remove(keyFor(kind));
            }
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_CLEAR, e);
        }
        if (!written) {
            throw guard.fail(OP_CLEAR, null);
        }
    }

    private static String keyFor(ConsumableKind kind) {
        return switch (kind) {
            case SITE -> KEY_SITE;
            case SENSOR -> KEY_SENSOR;
        };
    }
}
