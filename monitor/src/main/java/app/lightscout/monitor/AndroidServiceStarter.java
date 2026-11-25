package app.lightscout.monitor;

import android.content.Context;

import androidx.core.content.ContextCompat;

/**
 * {@link RuntimePorts.ServiceControl} over {@code startForegroundService} / {@code stopService}. A refused start
 * ({@code ForegroundServiceStartNotAllowedException} on API 31+, which is an {@code IllegalStateException}; the
 * background-start {@code IllegalStateException} below 31; a {@code SecurityException}) returns {@code false}.
 */
final class AndroidServiceStarter implements RuntimePorts.ServiceControl {

    private final Context context;

    /**
     * Creates the adapter.
     *
     * @param context any context (the application context is kept)
     */
    AndroidServiceStarter(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean start(StartOrigin origin) {
        try {
            ContextCompat.startForegroundService(context, MonitorIntents.service(context, origin));
            return true;
        } catch (IllegalStateException | SecurityException e) {
            return false;
        }
    }

    @Override
    public void stop() {
        context.stopService(MonitorIntents.service(context, StartOrigin.ACTIVITY));
    }
}
