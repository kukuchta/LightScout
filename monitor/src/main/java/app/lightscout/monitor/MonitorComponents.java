package app.lightscout.monitor;

import android.content.Context;

/**
 * Lookup helpers for the {@code :monitor} Android components: the {@link MonitorRuntimeProvider} (the
 * {@code Application}) and a {@link ServiceLauncher} built from the direct-boot-safe dependencies only (no container
 * build on a receiver's main thread).
 */
final class MonitorComponents {

    private MonitorComponents() {
    }

    /**
     * Returns the provider.
     *
     * @param context any context of this app
     * @return the provider
     * @throws IllegalStateException if the {@code Application} does not implement {@link MonitorRuntimeProvider}
     *                               (a composition error, CP-15)
     */
    static MonitorRuntimeProvider provider(Context context) {
        Context app = context.getApplicationContext();
        if (app instanceof MonitorRuntimeProvider provider) {
            return provider;
        }
        throw new IllegalStateException("Application must implement MonitorRuntimeProvider");
    }

    /**
     * Builds the alarm scheduler from the boot dependencies.
     *
     * @param context context
     * @param boot    boot dependencies
     * @return alarms
     */
    static MonitorAlarms alarms(Context context, MonitorBootDependencies boot) {
        return new MonitorAlarms(new AndroidAlarmPort(context), boot.clock(), boot.logger(),
                MonitorConfig.ALARM_CLOCK_FOR_EXPECTED_READING_TICK);
    }

    /**
     * Builds a launcher from the boot dependencies.
     *
     * @param context context
     * @param boot    boot dependencies
     * @return launcher
     */
    static ServiceLauncher launcher(Context context, MonitorBootDependencies boot) {
        return new ServiceLauncher(new AndroidServiceStarter(context), alarms(context, boot), boot.logger());
    }
}
