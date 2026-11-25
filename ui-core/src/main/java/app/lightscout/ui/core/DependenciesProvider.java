package app.lightscout.ui.core;

/**
 * Gives features access to IF-28 (CP-00A R-12). Implemented by the {@code Application} class in {@code :app};
 * features obtain it with {@code (DependenciesProvider) context.getApplicationContext()}.
 */
public interface DependenciesProvider {

    /**
     * Returns the process-wide dependency lookup.
     *
     * @return IF-28 {@link Dependencies}
     */
    Dependencies dependencies();
}
