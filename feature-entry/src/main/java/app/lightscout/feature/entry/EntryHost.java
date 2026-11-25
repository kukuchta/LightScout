package app.lightscout.feature.entry;

import androidx.fragment.app.Fragment;

import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.NavigatorHost;

/**
 * Access to IF-28 {@link Dependencies} (Application) and IF-29 {@link Navigator} (Activity) for S-02 (CP-00A R-12).
 */
public final class EntryHost {

    private EntryHost() {
    }

    /**
     * Returns the composition-root registry.
     *
     * @param fragment the calling fragment
     * @return the dependencies
     * @throws IllegalStateException if the Application does not provide them
     */
    public static Dependencies dependencies(Fragment fragment) {
        Object app = fragment.requireContext().getApplicationContext();
        if (!(app instanceof DependenciesProvider)) {
            throw new IllegalStateException("Application must implement DependenciesProvider (CP-00A R-12)");
        }
        return ((DependenciesProvider) app).dependencies();
    }

    /**
     * Returns the cross-feature navigator.
     *
     * @param fragment the calling fragment
     * @return the navigator
     * @throws IllegalStateException if the Activity does not provide one
     */
    public static Navigator navigator(Fragment fragment) {
        Object activity = fragment.requireActivity();
        if (!(activity instanceof NavigatorHost)) {
            throw new IllegalStateException("Activity must implement NavigatorHost (CP-00A R-12)");
        }
        return ((NavigatorHost) activity).navigator();
    }
}
