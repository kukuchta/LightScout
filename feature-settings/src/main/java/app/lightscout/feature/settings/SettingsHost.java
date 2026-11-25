package app.lightscout.feature.settings;

import androidx.fragment.app.Fragment;

import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.NavigatorHost;

/** Resolves IF-28 and IF-29 from the application composition root. */
final class SettingsHost {

    private SettingsHost() {
    }

    static Dependencies dependencies(Fragment fragment) {
        if (!(fragment.requireContext().getApplicationContext() instanceof DependenciesProvider provider)) {
            throw new IllegalStateException("Application must implement DependenciesProvider (CP-00A R-12)");
        }
        return provider.dependencies();
    }

    static Navigator navigator(Fragment fragment) {
        if (!(fragment.requireActivity() instanceof NavigatorHost host)) {
            throw new IllegalStateException("Activity must implement NavigatorHost (CP-00A R-12)");
        }
        return host.navigator();
    }
}
