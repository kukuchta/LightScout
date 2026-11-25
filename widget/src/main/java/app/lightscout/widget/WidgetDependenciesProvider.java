package app.lightscout.widget;

/**
 * App composition-root hook used by the manifest-declared widget provider.
 */
public interface WidgetDependenciesProvider {

    /**
     * Returns the process-wide widget updater after user unlock.
     *
     * @return the updater, or null while credential-encrypted storage is unavailable
     */
    GlucoseWidgetUpdater glucoseWidgetUpdater();
}
