package app.lightscout.feature.onboarding;

import androidx.fragment.app.Fragment;

import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.NavigatorHost;

/**
 * Access to the composition root from onboarding fragments: IF-28 {@link Dependencies} from the
 * {@code Application} ({@link DependenciesProvider}) and IF-29 {@link Navigator} from the hosting activity
 * ({@link NavigatorHost}) — CP-00 R-4, R-6; CP-00A R-12.
 */
public final class OnboardingHost {

    private OnboardingHost() {
    }

    /**
     * Returns the process-wide dependency lookup.
     *
     * @param fragment an attached fragment
     * @return IF-28
     * @throws IllegalStateException if the application does not implement {@link DependenciesProvider}
     */
    public static Dependencies dependencies(Fragment fragment) {
        Object app = fragment.requireContext().getApplicationContext();
        if (!(app instanceof DependenciesProvider)) {
            throw new IllegalStateException("Application must implement DependenciesProvider (CP-00A R-12)");
        }
        return ((DependenciesProvider) app).dependencies();
    }

    /**
     * Returns the navigator of the hosting activity.
     *
     * @param fragment an attached fragment
     * @return IF-29
     * @throws IllegalStateException if the activity does not implement {@link NavigatorHost}
     */
    public static Navigator navigator(Fragment fragment) {
        Object activity = fragment.requireActivity();
        if (!(activity instanceof NavigatorHost)) {
            throw new IllegalStateException("Activity must implement NavigatorHost (CP-00A R-12)");
        }
        return ((NavigatorHost) activity).navigator();
    }
}
