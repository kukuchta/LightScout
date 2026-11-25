package app.lightscout.feature.stats;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentContainerView;
import androidx.navigation.fragment.NavHostFragment;

/** Minimal test host for the S-03 nested graph. */
public final class StatisticsTestHostActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FragmentContainerView container = new FragmentContainerView(this);
        container.setId(app.lightscout.feature.stats.test.R.id.statistics_test_nav_host);
        setContentView(container);
        if (savedInstanceState == null) {
            NavHostFragment host = NavHostFragment.create(app.lightscout.feature.stats.R.navigation.stats_graph);
            getSupportFragmentManager().beginTransaction()
                    .replace(app.lightscout.feature.stats.test.R.id.statistics_test_nav_host, host)
                    .setPrimaryNavigationFragment(host)
                    .commitNow();
        }
    }
}
