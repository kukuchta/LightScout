package app.lightscout.feature.home;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentContainerView;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.NavResults;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.NavigatorHost;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test stand-in for {@code MainActivity} (CP-15): hosts {@code home_graph} in a {@link NavHostFragment} and implements
 * IF-29 with a recording navigator that stays on S-01 (the target screens belong to other slices).
 */
public class TestHostActivity extends AppCompatActivity implements NavigatorHost {

    /** IF-29 calls in order, e.g. „statistics”, „readiness:CHECK”. */
    static final List<String> NAVIGATIONS = new CopyOnWriteArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FragmentContainerView container = new FragmentContainerView(this);
        container.setId(app.lightscout.feature.home.test.R.id.test_nav_host);
        setContentView(container);
        if (savedInstanceState == null) {
            NavHostFragment host = NavHostFragment.create(R.navigation.home_graph);
            getSupportFragmentManager().beginTransaction()
                    .replace(app.lightscout.feature.home.test.R.id.test_nav_host, host)
                    .setPrimaryNavigationFragment(host)
                    .commitNow();
        }
    }

    NavController navController() {
        return ((NavHostFragment) getSupportFragmentManager()
                .findFragmentById(app.lightscout.feature.home.test.R.id.test_nav_host)).getNavController();
    }

    /**
     * Delivers a result message to S-01 the way {@code Navigator.finishWithResult} does (CP-00A R-38).
     *
     * @param message the message
     */
    void deliverResult(UiMessage message) {
        NavBackStackEntry entry = navController().getCurrentBackStackEntry();
        if (entry != null) {
            entry.getSavedStateHandle().set(NavResults.KEY, message);
        }
    }

    @Override
    public Navigator navigator() {
        return new Navigator() {
            @Override
            public void openEntry(String identifierOrNull) {
                NAVIGATIONS.add("entry:" + identifierOrNull);
            }

            @Override
            public void openNewEntry(app.lightscout.domain.model.EntryType initialType) {
                NAVIGATIONS.add("newEntry:" + initialType);
            }

            @Override
            public void openStatistics() {
                NAVIGATIONS.add("statistics");
            }

            @Override
            public void openSettings() {
                NAVIGATIONS.add("settings");
            }

            @Override
            public void openConnection(ConnectionMode mode) {
                NAVIGATIONS.add("connection:" + mode);
            }

            @Override
            public void openReadiness(ReadinessMode mode) {
                NAVIGATIONS.add("readiness:" + mode);
            }

            @Override
            public void finishOnboarding() {
                throw new AssertionError("not used by S-01");
            }

            @Override
            public void finishWithResult(UiMessage message) {
                throw new AssertionError("not used by S-01");
            }
        };
    }
}
