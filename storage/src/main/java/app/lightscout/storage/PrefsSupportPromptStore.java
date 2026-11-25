package app.lightscout.storage;

import android.content.SharedPreferences;

import java.time.Instant;

import app.lightscout.domain.port.SupportPromptStore;

/**
 * {@link SupportPromptStore} (DCR-46, D-166): the first-use instant and the „shown” flag of the one-time support
 * prompt, two keys in the ST-04 {@code runtime} preferences (credential-encrypted, after unlock only), written with
 * {@code commit()}. Not part of the reset on a server change: a reconnect does not bring the prompt back.
 */
final class PrefsSupportPromptStore implements SupportPromptStore {

    /** ST-04 key: epoch milliseconds of the first use. */
    static final String KEY_FIRST_USE_MS = "supportPromptFirstUseMs";
    /** ST-04 key: the prompt was shown. */
    static final String KEY_SHOWN = "supportPromptShown";

    private static final String OP_FIRST_USE = "support_prompt.first_use";
    private static final String OP_SHOWN = "support_prompt.shown";
    private static final String OP_MARK = "support_prompt.mark";

    private final SharedPreferences prefs;
    private final StoreGuard guard;

    /**
     * @param prefs the CE {@code runtime} preferences
     * @param guard failure logging and mapping (R-20)
     */
    PrefsSupportPromptStore(SharedPreferences prefs, StoreGuard guard) {
        this.prefs = prefs;
        this.guard = guard;
    }

    @Override
    public Instant firstUse(Instant now) {
        boolean written;
        try {
            if (prefs.contains(KEY_FIRST_USE_MS)) {
                return Instant.ofEpochMilli(prefs.getLong(KEY_FIRST_USE_MS, now.toEpochMilli()));
            }
            SharedPreferences.Editor editor = prefs.edit().putLong(KEY_FIRST_USE_MS, now.toEpochMilli());
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_FIRST_USE, e);
        }
        if (!written) {
            throw guard.fail(OP_FIRST_USE, null);
        }
        return now;
    }

    @Override
    public boolean shown() {
        try {
            return prefs.getBoolean(KEY_SHOWN, false);
        } catch (RuntimeException e) {
            throw guard.fail(OP_SHOWN, e);
        }
    }

    @Override
    public void markShown() {
        boolean written;
        try {
            SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_SHOWN, true);
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_MARK, e);
        }
        if (!written) {
            throw guard.fail(OP_MARK, null);
        }
    }
}
