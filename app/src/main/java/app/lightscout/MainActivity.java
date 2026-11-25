package app.lightscout;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.NavController;

import app.lightscout.alarm.AlarmLaunch;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.settings.ThemeMode;
import app.lightscout.navigation.AppNavigator;
import app.lightscout.navigation.NavControllerPort;
import app.lightscout.navigation.NavRequest;
import app.lightscout.navigation.NavTarget;
import app.lightscout.navigation.RootNavHostFragment;
import app.lightscout.navigation.StartRoute;
import app.lightscout.storage.StoredThemeMode;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.NavigatorHost;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.StartDestination;
import java.util.concurrent.Executor;

/**
 * The single activity for S-01…S-08 (CMP-81, ADR-022, CP-15 §8.3). Hosts {@code root_graph}, which includes the
 * feature graphs ({@code home_graph}, {@code onboarding_graph}); implements {@link NavigatorHost} (IF-29).
 *
 * <ul>
 *   <li>Exported with MAIN / LAUNCHER only; it never reads extras or data of any intent (SR-20, SR-21) and never
 *       shows over the keyguard (SR-25). Portrait only, {@code adjustResize}, edge-to-edge, predictive back.</li>
 *   <li>SR-21 hardening (BUG-05, Q-94; BUG-08, Q-95): the incoming intent is replaced by a clean one first thing in
 *       {@code onCreate}, before the graph is set, and again in {@code onNewIntent} before anything else sees it, so
 *       NavController's automatic deep-link handling never sees external data or extras (the graphs carry internal
 *       deep links for S-06 / S-07). The clean intent names this component and keeps only {@code ACTION_MAIN} /
 *       {@code CATEGORY_LAUNCHER} when the incoming intent has them ({@link CleanLaunch}); no data, type, extras,
 *       clip data, selector, other action or other category.</li>
 *   <li>Start destination (R-38 runtime start, Q-94): no graph is set until IF-27 has resolved on
 *       {@code ioExecutor} (StrictMode clean; window background as splash); then {@code root_graph} is set with its
 *       start = {@code home_graph} or {@code onboarding_graph} ({@link StartRoute}), so Up on S-01 returns
 *       {@code false}. A restored back stack is set again with the saved start and IF-27 is not called (§7.1).
 *       BUG-12: the saved start is read before {@code super.onCreate} and handed to {@link RootNavHostFragment}
 *       (FragmentFactory), which sets the graph before the FragmentManager restores the child fragments, so a
 *       re-created S-05…S-08 / S-01 finds its graph entries (graph-scoped ViewModels, SavedState).</li>
 *   <li>{@code onStart}: APP_OPEN path ({@link AppOpenFlow}). {@code onResume}: a live alarm screen opens S-09 (R-36,
 *       06 §11 F11; IR-109: alarm runtime state, not the IF-21 snapshot). {@code onNewIntent} (IF-50 clear-top
 *       taps): back to S-01 if it is in the back stack.</li>
 * </ul>
 */
public final class MainActivity extends FragmentActivity implements NavigatorHost {

    /** Activity-state key of the root start destination ({@link StartRoute#saveStart}). */
    private static final String KEY_ROOT_START = "app.lightscout.MainActivity.rootStart";

    private AppContainer container;
    private NavController navController;
    private NavControllerPort port;
    private AppNavigator navigator;
    private AppOpenFlow appOpenFlow;
    private boolean resolving;
    private boolean graphSet;
    private StartDestination pendingStart;

    /**
     * Applies the stored appearance (Settings > Theme): light or dark forces the matching resources for this
     * activity only, whatever the system says; "system default" changes nothing. Read before any resource is used.
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        ThemeMode mode = StoredThemeMode.read(newBase);
        // Dialogs, bottom sheets and pickers are AppCompat dialogs: they resolve night mode through AppCompatDelegate
        // (system value unless told otherwise), not through this activity's configuration. Same value for both.
        AppCompatDelegate.setDefaultNightMode(switch (mode) {
            case LIGHT -> AppCompatDelegate.MODE_NIGHT_NO;
            case DARK -> AppCompatDelegate.MODE_NIGHT_YES;
            case SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        });
        if (mode != ThemeMode.SYSTEM) {
            Configuration override = new Configuration();
            int current = newBase.getResources().getConfiguration().uiMode;
            override.uiMode = (mode == ThemeMode.DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO)
                    | (current & ~Configuration.UI_MODE_NIGHT_MASK);
            applyOverrideConfiguration(override);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setIntent(cleanIntent(getIntent())); // SR-21: before the graph is set (and before anything else reads it)
        NavTarget restored = savedInstanceState == null ? null
                : StartRoute.restoredStart(savedInstanceState.getString(KEY_ROOT_START));
        // BUG-12: super.onCreate restores the fragments; the host sets the restored root graph before its children.
        getSupportFragmentManager().setFragmentFactory(new RootNavHostFragment.Factory(restored));
        super.onCreate(savedInstanceState);
        EdgeToEdgeInsets.enable(this);
        setContentView(R.layout.activity_main);
        container = ((LightScoutApplication) getApplication()).container();
        RootNavHostFragment host = (RootNavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host);
        navController = host.getNavController();
        port = new NavControllerPort(navController, BuildConfig.DEBUG);
        navigator = new AppNavigator(port, container.logger());
        Executor main = ContextCompat.getMainExecutor(this);
        appOpenFlow = new AppOpenFlow(container.ioExecutor(), main, container.flags(), container::monitorControl,
                container::bootCountCheck);
        if (StartRoute.needsResolve(restored)) {
            resolveStartDestination(main);
        } else {
            if (!host.isRootGraphSet()) {
                port.setRootGraph(restored); // defensive: the host is always created with the restored start
            }
            graphSet = true; // restored back stack; IF-27 is not resolved again
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        appOpenFlow.onStart(() -> getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingStart != null) {
            StartDestination start = pendingStart;
            pendingStart = null;
            applyStartDestination(start);
        }
        // IR-109: the alarm runtime's live screen state (the source S-09 itself uses), not the IF-21 snapshot.
        AlarmType ringing = container.liveAlarmScreen();
        if (ringing != null) {
            startActivity(AlarmLaunch.screenIntent(this, ringing));
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        // SR-21: extras and data are dropped before NavController or any listener sees the intent.
        Intent clean = cleanIntent(intent);
        setIntent(clean);
        super.onNewIntent(clean);
        // IF-50: every notification / widget tap opens S-01.
        // Pops to homeFragment when it is in the back stack; otherwise (onboarding) nothing changes.
        if (graphSet) {
            navController.popBackStack(app.lightscout.feature.home.R.id.homeFragment, false);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (graphSet) {
            outState.putString(KEY_ROOT_START, StartRoute.saveStart(port.rootStart()));
        }
    }

    @Override
    public Navigator navigator() {
        return navigator;
    }

    /**
     * SR-21 (Q-95): an intent naming only this activity, plus {@code ACTION_MAIN} / {@code CATEGORY_LAUNCHER} when the
     * incoming intent has them (no payload). Only the action and the categories of {@code incoming} are read; data,
     * type, extras, clip data, selector, flags and every other action or category are dropped.
     */
    private Intent cleanIntent(Intent incoming) {
        CleanLaunch keep = incoming == null ? CleanLaunch.of(null, null)
                : CleanLaunch.of(incoming.getAction(), incoming.getCategories());
        Intent clean = new Intent().setComponent(getComponentName());
        if (keep.action() != null) {
            clean.setAction(keep.action());
        }
        for (String category : keep.categories()) {
            clean.addCategory(category);
        }
        return clean;
    }

    private void resolveStartDestination(Executor main) {
        if (resolving) {
            return;
        }
        resolving = true;
        OnboardingState onboarding = container.onboardingState();
        container.ioExecutor().execute(() -> {
            StartDestination destination = onboarding.resolve();
            main.execute(() -> {
                resolving = false;
                applyStartDestination(destination);
            });
        });
    }

    private void applyStartDestination(StartDestination destination) {
        if (isFinishing() || isDestroyed() || graphSet) {
            return;
        }
        if (getSupportFragmentManager().isStateSaved()) {
            pendingStart = destination; // applied in onResume (no fragment transaction after the state is saved)
            return;
        }
        port.setRootGraph(StartRoute.startGraph(destination));
        graphSet = true;
        for (NavRequest step : StartRoute.plan(destination)) {
            port.navigate(step);
        }
    }
}
