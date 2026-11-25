package app.lightscout.alarm;

import android.content.Context;

/**
 * Lookup of the {@link AlarmRuntimeProvider} (the {@code Application}) for the {@code :alarm} Android components.
 */
final class AlarmComponents {

    private AlarmComponents() {
    }

    /**
     * Returns the process-wide runtime.
     *
     * @param context any context of this app
     * @return the runtime
     * @throws IllegalStateException if the {@code Application} does not implement {@link AlarmRuntimeProvider}
     *                               (a composition error, CP-15)
     */
    static AlarmRuntime runtime(Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof AlarmRuntimeProvider provider) {
            return provider.alarmRuntime();
        }
        throw new IllegalStateException("Application must implement AlarmRuntimeProvider");
    }
}
