package app.lightscout.feature.entry;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.NavigatorHost;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test stand-in for {@code MainActivity} (CP-15): hosts {@code entry_graph} in a {@link NavHostFragment} and
 * implements IF-29 with a recording navigator. {@link #EXTRA_ENTRY_ID} opens S-02 in edit mode (Safe Args
 * {@code entryId}). {@link #restoreNextFrom(Bundle)} makes the next instance start from a saved-instance-state
 * bundle with a fresh ViewModel store — the process-death path of TST-06.
 */
public class TestHostActivity extends AppCompatActivity implements NavigatorHost {

    /** Intent extra: the entry id (edit mode); absent = create. */
    static final String EXTRA_ENTRY_ID = "entryId";
    /** IF-29 {@code finishWithResult} calls (S-02 → S-01). */
    static final List<UiMessage> RESULTS = new CopyOnWriteArrayList<>();

    private static final AtomicReference<Bundle> RESTORE = new AtomicReference<>();

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
        container.setId(app.lightscout.feature.entry.test.R.id.test_nav_host);
        setContentView(container);
        if (state == null) {
            String id = getIntent().getStringExtra(EXTRA_ENTRY_ID);
            NavHostFragment host = NavHostFragment.create(R.navigation.entry_graph,
                    new EntryFormFragmentArgs.Builder(id == null ? "CREATE" : "EDIT")
                            .setEntryId(id).setInitialType(id == null
                                    ? getIntent().getStringExtra("initialType") == null ? "CARBS"
                                            : getIntent().getStringExtra("initialType") : null)
                            .build().toBundle());
            getSupportFragmentManager().beginTransaction()
                    .replace(app.lightscout.feature.entry.test.R.id.test_nav_host, host)
                    .setPrimaryNavigationFragment(host)
                    .commitNow();
        }
    }

    NavController navController() {
        return host().getNavController();
    }

    /** The S-02 fragment, or {@code null} once the form was closed. */
    EntryFormFragment form() {
        if (host() == null) {
            return null;
        }
        for (Fragment f : host().getChildFragmentManager().getFragments()) {
            if (f instanceof EntryFormFragment && f.isAdded()) {
                return (EntryFormFragment) f;
            }
        }
        return null;
    }

    @Override
    public <T extends android.view.View> T findViewById(int id) {
        T view = super.findViewById(id);
        if (view != null) {
            return view;
        }
        EntryFormFragment f = form();
        return f == null || f.getDialog() == null ? null : f.getDialog().findViewById(id);
    }

    /** {@code true} once S-02 left the back stack (close / finish). */
    boolean closed() {
        return navController().getCurrentDestination() == null
                || navController().getCurrentDestination().getId() != R.id.entryFormFragment;
    }

    private NavHostFragment host() {
        return (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(app.lightscout.feature.entry.test.R.id.test_nav_host);
    }

    @Override
    public Navigator navigator() {
        return new Navigator() {
            @Override
            public void openEntry(String identifierOrNull) {
                throw new AssertionError("not used inside the graph");
            }

            @Override
            public void openNewEntry(app.lightscout.domain.model.EntryType initialType) {
                throw new AssertionError("not used inside the graph");
            }

            @Override
            public void openStatistics() {
                throw new AssertionError("not used by S-02");
            }

            @Override
            public void openSettings() {
                throw new AssertionError("not used by S-02");
            }

            @Override
            public void openConnection(ConnectionMode mode) {
                throw new AssertionError("not used by S-02");
            }

            @Override
            public void openReadiness(ReadinessMode mode) {
                throw new AssertionError("not used by S-02");
            }

            @Override
            public void finishOnboarding() {
                throw new AssertionError("not used by S-02");
            }

            @Override
            public void finishWithResult(UiMessage message) {
                RESULTS.add(message);
                navController().popBackStack();
            }
        };
    }
}
