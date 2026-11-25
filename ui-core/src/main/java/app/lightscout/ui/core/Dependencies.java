package app.lightscout.ui.core;

import java.util.concurrent.Executor;

/**
 * IF-28 — typed dependency lookup for features (CP-00A R-12, ADR-023, ADR-027; CP-00 R-6).
 *
 * <p>Implemented by {@code AppContainer} in {@code :app} (CP-15). {@code :ui-core} may not depend on
 * {@code :usecases}, so features ask for the use-case interface by class. Registered types: IF-21…IF-27
 * interfaces, {@code ReadinessChecker}, {@code NotificationChannels}, {@code SystemSettingsIntents},
 * {@code ManufacturerDetector} and {@code Clock}.
 */
public interface Dependencies {

    /**
     * Returns the registered instance of a type.
     *
     * @param type the interface asked for
     * @param <T>  its type
     * @return the instance, never {@code null}
     * @throws IllegalStateException if the type is not registered (a programming error caught by tests)
     */
    <T> T get(Class<T> type);

    /**
     * Executor for blocking use-case calls from ViewModels (CP-00A R-34); results are published with
     * {@code LiveData.postValue}.
     *
     * @return the shared {@code ioExecutor}
     */
    Executor ioExecutor();

    /**
     * Application facts shown in the UI.
     *
     * @return the app information
     */
    AppInfo appInfo();
}
