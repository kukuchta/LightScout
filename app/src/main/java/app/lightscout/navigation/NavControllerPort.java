package app.lightscout.navigation;

import android.net.Uri;
import android.os.Bundle;

import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDeepLinkRequest;
import androidx.navigation.NavDestination;
import androidx.navigation.NavGraph;
import androidx.navigation.NavOptions;

import app.lightscout.R;
import app.lightscout.ui.core.NavResults;
import app.lightscout.ui.core.UiMessage;
import java.util.Objects;

/**
 * {@link NavPort} over the {@code MainActivity} {@link NavController} (CP-00A R-38; BUG-05, ruling Q-94).
 *
 * <ul>
 *   <li>Graph targets ({@code home_graph}, {@code onboarding_graph}, {@code entry_graph}, {@code stats_graph},
 *       {@code settings_graph}) are children of {@code root_graph} and are reached by id ({@code entry_graph} with
 *       its {@code entryId} argument). S-06 / S-07
 *       live inside {@code onboarding_graph}, which Navigation does not search from a
 *       sibling graph, so they are reached with a {@link NavDeepLinkRequest} on their internal URI
 *       ({@link NavLinks}); the arguments are parsed by the Safe Args enum types of {@code :feature-onboarding}. The
 *       NavController adds the {@code onboarding_graph} entry on top of the caller, so the caller (S-01, later S-04)
 *       stays below, Back returns to it and {@link #finishWithResult} reaches it.</li>
 *   <li>The root graph is set once, with its start destination resolved at runtime ({@link #setRootGraph}); a
 *       step that replaces a graph moves the root start with it ({@link StartRoute#rootStartAfter}).</li>
 *   <li>No silent failure: when {@code strict} (debug builds) a navigation exception is rethrown. In release a
 *       failing step is a no-op (no log line — closed event catalogue R-13).</li>
 * </ul>
 * Main thread only.
 */
public final class NavControllerPort implements NavPort {

    /** Safe Args argument name of {@code entry_graph} / S-02 ({@code entryId}, nullable string). */
    static final String ENTRY_ID = "entryId";

    private final NavController navController;
    private final boolean strict;

    /**
     * Creates the adapter.
     *
     * @param navController the activity's controller
     * @param strict        {@code BuildConfig.DEBUG}: rethrow navigation failures
     */
    public NavControllerPort(NavController navController, boolean strict) {
        this.navController = Objects.requireNonNull(navController, "navController");
        this.strict = strict;
    }

    /**
     * Returns the destination id of a target.
     *
     * @param target the target
     * @return the resource id
     */
    public static int destinationId(NavTarget target) {
        switch (target) {
            case HOME:
                return app.lightscout.feature.home.R.id.home_graph;
            case ONBOARDING:
                return app.lightscout.feature.onboarding.R.id.onboarding_graph;
            case CONNECTION:
                return app.lightscout.feature.onboarding.R.id.connectionFragment;
            case READINESS:
                return app.lightscout.feature.onboarding.R.id.readinessFragment;
            case STATISTICS:
                return app.lightscout.feature.stats.R.id.stats_graph;
            case SETTINGS:
                return app.lightscout.feature.settings.R.id.settings_graph;
            case ENTRY:
                return app.lightscout.feature.entry.R.id.entry_graph;
            default:
                throw new IllegalArgumentException("Unknown navigation target " + target);
        }
    }

    /**
     * Inflates {@code root_graph}, sets its start destination and hands it to the NavController (R-38 runtime
     * start). With a restored NavController state the restored back stack is used (the start only keeps Up
     * consistent).
     *
     * @param start HOME or ONBOARDING
     */
    public void setRootGraph(NavTarget start) {
        setRootGraph(navController, start);
    }

    /**
     * {@link #setRootGraph(NavTarget)} for a controller without an adapter: used by {@link RootNavHostFragment}, which
     * sets the restored root graph before its child fragments are restored (BUG-12).
     *
     * @param navController the controller
     * @param start         HOME or ONBOARDING
     */
    static void setRootGraph(NavController navController, NavTarget start) {
        if (start != NavTarget.HOME && start != NavTarget.ONBOARDING) {
            throw new IllegalArgumentException("root start must be a graph: " + start);
        }
        NavGraph graph = navController.getNavInflater().inflate(R.navigation.root_graph);
        graph.setStartDestination(destinationId(start));
        navController.setGraph(graph, null);
    }

    /**
     * Returns the current root start.
     *
     * @return HOME or ONBOARDING
     * @throws IllegalStateException if no graph is set
     */
    public NavTarget rootStart() {
        int start = navController.getGraph().getStartDestinationId();
        return start == destinationId(NavTarget.HOME) ? NavTarget.HOME : NavTarget.ONBOARDING;
    }

    @Override
    public void navigate(NavRequest request) {
        int id = destinationId(request.target());
        NavDestination current = navController.getCurrentDestination();
        if (request.popUpTo() == null && current != null && (current.getId() == id
                || request.target() == NavTarget.ENTRY && current.getParent() != null
                && current.getParent().getId() == id)) {
            return; // already there (double tap, GIR-1): never stack the same destination twice
        }
        try {
            if (NavLinks.isDeepLink(request)) {
                navController.navigate(NavDeepLinkRequest.Builder.fromUri(Uri.parse(NavLinks.uri(request))).build());
            } else {
                NavOptions options = null;
                if (request.popUpTo() != null) {
                    options = new NavOptions.Builder()
                            .setPopUpTo(destinationId(request.popUpTo()), request.popInclusive())
                            .build();
                }
                Bundle args = null;
                if (request.target() == NavTarget.ENTRY) {
                    args = new Bundle();
                    args.putString(ENTRY_ID, request.entryId());
                }
                navController.navigate(id, args, options);
            }
            NavTarget start = StartRoute.rootStartAfter(request);
            if (start != null) {
                navController.getGraph().setStartDestination(destinationId(start));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            if (strict) {
                throw e;
            }
            // release: the step is not executed (no log line, R-13); the user stays on the current screen.
        }
    }

    @Override
    public void finishWithResult(UiMessage message) {
        NavDestination current = navController.getCurrentDestination();
        if (message != UiMessage.CONNECTION_SAVED && (current == null
                || current.getId() != app.lightscout.feature.entry.R.id.entryFormFragment)) {
            return;
        }
        NavBackStackEntry previous = navController.getPreviousBackStackEntry();
        if (previous != null) {
            previous.getSavedStateHandle().set(NavResults.KEY, message);
        }
        navController.popBackStack();
    }
}
