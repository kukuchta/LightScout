package app.lightscout.feature.stats;

import androidx.fragment.app.Fragment;

import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;

/** Resolves the shared IF-28 dependencies from the application composition root. */
final class StatisticsHost {

    private StatisticsHost() {
    }

    static Dependencies dependencies(Fragment fragment) {
        if (!(fragment.requireActivity().getApplication() instanceof DependenciesProvider provider)) {
            throw new IllegalStateException("Application must implement DependenciesProvider (CP-00A R-12)");
        }
        return provider.dependencies();
    }
}
