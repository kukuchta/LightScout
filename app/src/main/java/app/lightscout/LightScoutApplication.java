package app.lightscout;

import android.app.Application;

import app.lightscout.alarm.AlarmRuntime;
import app.lightscout.alarm.AlarmRuntimeProvider;
import app.lightscout.domain.port.Logger;
import app.lightscout.monitor.MonitorBootDependencies;
import app.lightscout.monitor.MonitorRuntime;
import app.lightscout.monitor.MonitorRuntimeProvider;
import app.lightscout.platform.CrashRecorder;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.widget.GlucoseWidgetUpdater;
import app.lightscout.widget.WidgetDependenciesProvider;

/**
 * The application class (CMP-80): owns the one {@link AppContainer} of the process and exposes it to the modules
 * through their provider interfaces — IF-28 {@link DependenciesProvider} (features), {@link MonitorRuntimeProvider}
 * ({@code :monitor}, SS-09 §5) and {@link AlarmRuntimeProvider} ({@code :alarm}, SS-10 §5).
 *
 * <p>{@link #onCreate()} runs in every process start, also the direct-boot start for {@code LOCKED_BOOT_COMPLETED}
 * (WF-04), so it is locked-safe (CP-15 §8.1, CP-00 §6): {@code CrashRecorder} first (CP-00A R-86), then debug
 * StrictMode, then the lazy container (only the logger, clock, channel registry and a deferred flag store), then the
 * channels (06 §3). It never touches CE prefs, SQLite, Keystore or the ring buffer, never starts monitoring and never
 * runs the boot-count check (Q-67: that runs only from {@code MainActivity.onStart}).
 */
public class LightScoutApplication extends Application
        implements DependenciesProvider, MonitorRuntimeProvider, AlarmRuntimeProvider, WidgetDependenciesProvider {

    private static final String TAG = "LightScoutApplication";

    private AppContainer container;
    private GlucoseWidgetUpdater widgetUpdater;

    @Override
    public void onCreate() {
        super.onCreate();
        ContainerComponents components = createComponents();
        Logger logger = components.logger();
        CrashRecorder.install(logger);
        if (BuildConfig.DEBUG) {
            DebugStrictMode.install(logger);   // after the logger: a StrictMode kill writes the crash line (IR-117)
        }
        container = new AppContainer(components);
        boolean unlocked = components.isUserUnlocked();
        try {
            container.channels().ensureChannels();
        } catch (RuntimeException e) {
            // never crash the process start (also the direct-boot start); every CH-02 poster ensures again (BUG-16)
            logger.crash(e.getClass().getSimpleName(), "LightScoutApplication.ensureChannels");   // release trace
        }
        logger.debug(TAG, unlocked ? "process start (unlocked)" : "process start (locked: CE components deferred)");
    }

    /**
     * Creates the leaf-adapter factory of the container. Instrumented tests override it with fakes (in-memory stores,
     * no network) through their own application subclass.
     *
     * @return the factory
     */
    protected ContainerComponents createComponents() {
        return new AndroidComponents(this, BuildConfig.DEBUG, BuildConfig.VERSION_NAME);
    }

    /**
     * Returns the process-wide container.
     *
     * @return the container
     */
    public AppContainer container() {
        return container;
    }

    @Override
    public Dependencies dependencies() {
        glucoseWidgetUpdater();
        return container;
    }

    @Override
    public synchronized GlucoseWidgetUpdater glucoseWidgetUpdater() {
        if (!container.isUserUnlocked()) {
            return null;
        }
        if (widgetUpdater == null) {
            widgetUpdater = container.glucoseWidgetUpdater(this);
            registerComponentCallbacks(widgetUpdater);
            widgetUpdater.updateWidgets(null);
        }
        return widgetUpdater;
    }

    @Override
    public MonitorBootDependencies monitorBootDependencies() {
        return container.monitorBootDependencies();
    }

    @Override
    public MonitorRuntime monitorRuntime() {
        return container.monitorRuntime();
    }

    @Override
    public AlarmRuntime alarmRuntime() {
        return container.alarmRuntime();
    }
}
