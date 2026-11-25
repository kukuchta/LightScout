package app.lightscout.navigation;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.navigation.fragment.NavHostFragment;

/**
 * The {@code NavHostFragment} of {@code MainActivity} (CMP-81; BUG-12, keeps the BUG-05 / Q-94 runtime root start).
 *
 * <p>Q-94 sets {@code root_graph} at runtime (no {@code app:navGraph}). When the activity is re-created (process
 * death, configuration change) the FragmentManager restores this fragment inside {@code MainActivity}'s
 * {@code super.onCreate}, and the base {@code NavHostFragment.onCreate} restores the child fragments (S-05…S-08,
 * S-01) at its end. Those children may scope ViewModels to a graph entry ({@code getBackStackEntry(onboarding_graph)}
 * in S-06), so the graph must exist before that point. This fragment therefore sets the root graph with the saved
 * root start ({@link StartRoute#restoredStart}) in its own {@code onCreate}, after the NavController has restored its
 * saved back stack and before the children are restored — the same order as a stock {@code NavHostFragment} with
 * {@code app:navGraph}. The restored back stack is used; the start only keeps Up consistent.
 *
 * <p>Without a restored root start (cold start, or a re-creation before the first IF-27 resolve finished) it sets no
 * graph: {@code MainActivity} resolves IF-27 and sets the graph itself (R-38 runtime start). The restored root start
 * comes from {@code MainActivity}'s saved state, handed over by {@link Factory} (installed before
 * {@code super.onCreate}). Main thread only.
 */
public final class RootNavHostFragment extends NavHostFragment {

    private NavTarget restoredStart;
    private boolean rootGraphSet;

    /** Creates the fragment (no-argument constructor required by the fragment framework). */
    public RootNavHostFragment() {
        super();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        if (restoredStart != null) {
            // getNavController() creates the NavHostController and restores its saved state (back stack) first;
            // setGraph then rebuilds the restored back stack instead of navigating to the start.
            NavControllerPort.setRootGraph(getNavController(), restoredStart);
            rootGraphSet = true;
        }
        super.onCreate(savedInstanceState); // restores the child fragments: the graph already exists (BUG-12)
    }

    /**
     * Tells whether this fragment set the root graph from a restored root start.
     *
     * @return {@code true} after {@code onCreate} with a restored root start
     */
    public boolean isRootGraphSet() {
        return rootGraphSet;
    }

    /**
     * Creates {@link RootNavHostFragment} with the root start restored from the activity state; every other class
     * is created as usual. Install with {@code getSupportFragmentManager().setFragmentFactory} before
     * {@code super.onCreate}, so the FragmentManager uses it when it restores the fragment.
     */
    public static final class Factory extends FragmentFactory {

        private final NavTarget restoredStart;

        /**
         * Creates the factory.
         *
         * @param restoredStart the root start saved with the activity state (HOME / ONBOARDING), or {@code null}
         *                      when no graph was set before the re-creation (or on a cold start)
         */
        public Factory(@Nullable NavTarget restoredStart) {
            if (restoredStart != null && restoredStart != NavTarget.HOME && restoredStart != NavTarget.ONBOARDING) {
                throw new IllegalArgumentException("root start must be a graph: " + restoredStart);
            }
            this.restoredStart = restoredStart;
        }

        @NonNull
        @Override
        public Fragment instantiate(@NonNull ClassLoader classLoader, @NonNull String className) {
            Fragment fragment = super.instantiate(classLoader, className);
            if (fragment instanceof RootNavHostFragment) {
                ((RootNavHostFragment) fragment).restoredStart = restoredStart;
            }
            return fragment;
        }
    }
}
