package app.lightscout.storage;

import android.content.SharedPreferences;

import java.time.Instant;

import app.lightscout.domain.port.UpdateStore;
import app.lightscout.domain.update.AppVersion;

/**
 * {@link UpdateStore} (DCR-49): the on / off choice (default on), the time of the last check, the newest stable
 * version GitHub named and the newest version the dialog was shown for. Four keys in the ST-04 {@code runtime}
 * preferences (credential-encrypted, after unlock only), written with {@code commit()}. Not part of the reset on a
 * server change: connecting to another Nightscout does not change how the app updates. A stored version that is not
 * a plain {@code x.y.z} reads as unknown.
 */
final class PrefsUpdateStore implements UpdateStore {

    /** ST-04 key: the update check is on (default {@code true}). */
    static final String KEY_ENABLED = "updateCheckEnabled";
    /** ST-04 key: epoch milliseconds of the last check. */
    static final String KEY_LAST_CHECK_MS = "updateLastCheckMs";
    /** ST-04 key: the newest stable version GitHub named, {@code x.y.z}. */
    static final String KEY_LATEST = "updateLatest";
    /** ST-04 key: the newest version the dialog was shown for, {@code x.y.z}. */
    static final String KEY_PROMPTED = "updatePrompted";

    private static final String OP_READ = "update.read";
    private static final String OP_ENABLE = "update.enable";
    private static final String OP_RECORD = "update.record";
    private static final String OP_PROMPT = "update.prompt";

    private final SharedPreferences prefs;
    private final StoreGuard guard;

    /**
     * @param prefs the CE {@code runtime} preferences
     * @param guard failure logging and mapping (R-20)
     */
    PrefsUpdateStore(SharedPreferences prefs, StoreGuard guard) {
        this.prefs = prefs;
        this.guard = guard;
    }

    @Override
    public boolean enabled() {
        try {
            return prefs.getBoolean(KEY_ENABLED, true);
        } catch (RuntimeException e) {
            throw guard.fail(OP_READ, e);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        write(OP_ENABLE, prefs.edit().putBoolean(KEY_ENABLED, enabled));
    }

    @Override
    public Instant lastCheck() {
        try {
            return prefs.contains(KEY_LAST_CHECK_MS) ? Instant.ofEpochMilli(prefs.getLong(KEY_LAST_CHECK_MS, 0L))
                    : null;
        } catch (RuntimeException e) {
            throw guard.fail(OP_READ, e);
        }
    }

    @Override
    public AppVersion latest() {
        return version(KEY_LATEST);
    }

    @Override
    public AppVersion lastPrompted() {
        return version(KEY_PROMPTED);
    }

    @Override
    public void recordCheck(Instant checkedAt, AppVersion latest) {
        SharedPreferences.Editor editor = prefs.edit().putLong(KEY_LAST_CHECK_MS, checkedAt.toEpochMilli());
        if (latest != null) {
            editor.putString(KEY_LATEST, latest.toString());
        }
        write(OP_RECORD, editor);
    }

    @Override
    public void markPrompted(AppVersion version) {
        write(OP_PROMPT, prefs.edit().putString(KEY_PROMPTED, version.toString()));
    }

    private AppVersion version(String key) {
        try {
            return AppVersion.parseStable(prefs.getString(key, null)).orElse(null);
        } catch (RuntimeException e) {
            throw guard.fail(OP_READ, e);
        }
    }

    private void write(String operation, SharedPreferences.Editor editor) {
        boolean written;
        try {
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(operation, e);
        }
        if (!written) {
            throw guard.fail(operation, null);
        }
    }
}
