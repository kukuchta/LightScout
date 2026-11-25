package app.lightscout.feature.onboarding;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentContainerView;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import app.lightscout.feature.onboarding.connection.ConnectionFragmentArgs;
import app.lightscout.feature.onboarding.guide.ManufacturerGuideFragmentArgs;
import app.lightscout.feature.onboarding.readiness.ReadinessFragmentArgs;
import app.lightscout.platform.Manufacturer;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.NavigatorHost;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test stand-in for {@code MainActivity} (CP-15): hosts {@code onboarding_graph} in a {@link NavHostFragment} and
 * implements IF-29 with a recording navigator. {@link #EXTRA_EDIT} opens S-06 in edit mode on top of S-05;
 * {@link #EXTRA_READINESS} opens S-07 (with the given {@code ReadinessMode} name) on top of S-05;
 * {@link #EXTRA_GUIDE} opens S-08 (with the given {@code Manufacturer} name) on top of S-05.
 * {@link #restoreNextFrom(Bundle)} makes the next instance start from a saved-instance-state bundle with a fresh
 * ViewModel store — the process-death path of TST-06 (the old instance is gone, only the Bundle survives).
 */
public class TestHostActivity extends AppCompatActivity implements NavigatorHost {

    /** Intent extra: open S-06 (mode edit) after start. */
    static final String EXTRA_EDIT = "edit";
    /** Intent extra: open S-07 with this {@code ReadinessMode} name after start. */
    static final String EXTRA_READINESS = "readiness";
    /** Intent extra: open S-08 with this {@code Manufacturer} name after start. */
    static final String EXTRA_GUIDE = "guide";
    /** Calls of IF-29 {@code finishOnboarding()} (S-07 „Done”). */
    static final AtomicInteger FINISHED_ONBOARDING = new AtomicInteger();

    private static final AtomicReference<Bundle> RESTORE = new AtomicReference<>();
    static final List<UiMessage> RESULTS = new CopyOnWriteArrayList<>();

    /**
     * The next created instance restores from this bundle (simulated process death).
     *
     * @param saved a bundle produced by {@code onSaveInstanceState}
     */
    static void restoreNextFrom(Bundle saved) {
        RESTORE.set(saved);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Bundle restore = RESTORE.getAndSet(null);
        Bundle state = restore != null ? restore : savedInstanceState;
        super.onCreate(state);
        FragmentContainerView container = new FragmentContainerView(this);
        container.setId(app.lightscout.feature.onboarding.test.R.id.test_nav_host);
        setContentView(container);
        if (state == null) {
            NavHostFragment host = NavHostFragment.create(R.navigation.onboarding_graph);
            getSupportFragmentManager().beginTransaction()
                    .replace(app.lightscout.feature.onboarding.test.R.id.test_nav_host, host)
                    .setPrimaryNavigationFragment(host)
                    .commitNow();
            if (getIntent().getBooleanExtra(EXTRA_EDIT, false)) {
                host.getNavController().navigate(R.id.connectionFragment,
                        new ConnectionFragmentArgs.Builder().setMode(ConnectionMode.EDIT).build().toBundle());
            }
            String readiness = getIntent().getStringExtra(EXTRA_READINESS);
            if (readiness != null) {
                host.getNavController().navigate(R.id.readinessFragment, new ReadinessFragmentArgs.Builder()
                        .setMode(ReadinessMode.valueOf(readiness)).build().toBundle());
            }
            String guide = getIntent().getStringExtra(EXTRA_GUIDE);
            if (guide != null) {
                host.getNavController().navigate(R.id.manufacturerGuideFragment,
                        new ManufacturerGuideFragmentArgs.Builder(Manufacturer.valueOf(guide)).build().toBundle());
            }
        }
    }

    NavController navController() {
        return ((NavHostFragment) getSupportFragmentManager().findFragmentById(app.lightscout.feature.onboarding.test.R.id.test_nav_host)).getNavController();
    }

    @Override
    public Navigator navigator() {
        return new Navigator() {
            @Override
            public void openEntry(String identifierOrNull) {
                throw new AssertionError("not used by onboarding");
            }

            @Override
            public void openNewEntry(app.lightscout.domain.model.EntryType initialType) {
                throw new AssertionError("not used by onboarding");
            }

            @Override
            public void openStatistics() {
                throw new AssertionError("not used by onboarding");
            }

            @Override
            public void openSettings() {
                throw new AssertionError("not used by onboarding");
            }

            @Override
            public void openConnection(ConnectionMode mode) {
                throw new AssertionError("not used inside the graph");
            }

            @Override
            public void openReadiness(ReadinessMode mode) {
                throw new AssertionError("not used inside the graph");
            }

            @Override
            public void finishOnboarding() {
                FINISHED_ONBOARDING.incrementAndGet();
            }

            @Override
            public void finishWithResult(UiMessage message) {
                RESULTS.add(message);
                navController().popBackStack();
            }
        };
    }
}
