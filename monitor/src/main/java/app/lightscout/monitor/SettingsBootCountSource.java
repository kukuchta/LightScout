package app.lightscout.monitor;

import android.content.Context;
import android.provider.Settings;

/**
 * Reads {@code Settings.Global.BOOT_COUNT} (F5). Available while locked (no CE storage).
 */
final class SettingsBootCountSource implements RuntimePorts.BootCountSource {

    private final Context context;

    /**
     * Creates the source.
     *
     * @param context any context (the application context is kept)
     */
    SettingsBootCountSource(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public long bootCount() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
