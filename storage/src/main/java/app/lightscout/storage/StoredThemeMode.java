package app.lightscout.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StrictMode;

import app.lightscout.domain.settings.ThemeMode;

/**
 * Synchronous read of the stored appearance (ENT-05 {@code theme_mode}) for the activity's {@code attachBaseContext},
 * before any screen exists and before the async stores are ready. One small preferences read on the main thread
 * (the file is already cached by the settings store after the first call); a failure reads as „follow the system”.
 */
public final class StoredThemeMode {

    private StoredThemeMode() {
    }

    /**
     * Returns the stored appearance.
     *
     * @param context any context of this app
     * @return the mode; {@link ThemeMode#SYSTEM} when nothing is stored or the read fails
     */
    public static ThemeMode read(Context context) {
        StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskReads();
        try {
            SharedPreferences prefs = context.getSharedPreferences(StorageFactory.SETTINGS_PREFS, Context.MODE_PRIVATE);
            return ThemeMode.fromCode(prefs.getInt(PrefsSettingsStore.THEME_MODE, ThemeMode.SYSTEM.code()));
        } catch (RuntimeException e) {
            return ThemeMode.SYSTEM;
        } finally {
            StrictMode.setThreadPolicy(policy);
        }
    }
}
