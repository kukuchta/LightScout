package app.lightscout.navigation;

import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.usecases.StartDestination;
import java.util.List;
import java.util.Objects;

/**
 * Start destination and its back stack (CP-15 §7.1, CP-00A R-38 runtime start, R-72, SS-11 §7; BUG-05 / Q-94).
 *
 * <p>{@code MainActivity} sets no graph until the start is known (the window background acts as splash). On a cold
 * start without a restored back stack, IF-27 {@code resolve()} runs on {@code ioExecutor}; then the root graph is
 * inflated, its start destination is set to {@link #startGraph} and only then is it handed to the NavController, so
 * the root start always is the bottom graph of the back stack and {@code navigateUp()} never relaunches the task
 * (Navigation's generated-stack Up). {@link #plan} lists the steps pushed on top:
 * <ul>
 *   <li>DISCLAIMER → start {@code onboarding_graph} (S-05), no step;</li>
 *   <li>CONNECTION → start {@code onboarding_graph}, then S-06 (onboarding);</li>
 *   <li>READINESS → start {@code onboarding_graph}, then S-06, S-07 (onboarding; S-06 shows the stored URL + masked
 *       token itself);</li>
 *   <li>HOME → start {@code home_graph} (S-01), no step (also when the connection fails later: no automatic
 *       redirect, design §9.1).</li>
 * </ul>
 * After process death the restored Navigation back stack is used with the saved root start ({@link #restoredStart})
 * and IF-27 is <b>not</b> called ({@link #needsResolve}).
 */
public final class StartRoute {

    private StartRoute() {
    }

    /**
     * Tells whether IF-27 must be resolved: only when no graph was set before the activity was re-created (cold
     * start, or a process death before the first resolve finished). A restored back stack never resolves again
     * (03 §6.8, AR-50).
     *
     * @param restoredStart the root start saved with the activity state ({@link #restoredStart}); nullable
     * @return {@code true} to resolve
     */
    public static boolean needsResolve(NavTarget restoredStart) {
        return restoredStart == null;
    }

    /**
     * Returns the root graph's start destination for an IF-27 result.
     *
     * @param destination IF-27 result
     * @return {@link NavTarget#HOME} or {@link NavTarget#ONBOARDING}
     */
    public static NavTarget startGraph(StartDestination destination) {
        return Objects.requireNonNull(destination, "destination") == StartDestination.HOME
                ? NavTarget.HOME : NavTarget.ONBOARDING;
    }

    /**
     * Returns the steps pushed on top of the start graph.
     *
     * @param destination IF-27 result
     * @return navigation steps in order (possibly empty)
     */
    public static List<NavRequest> plan(StartDestination destination) {
        switch (Objects.requireNonNull(destination, "destination")) {
            case CONNECTION:
                return List.of(NavRequest.connection(ConnectionMode.ONBOARDING));
            case READINESS:
                return List.of(NavRequest.connection(ConnectionMode.ONBOARDING),
                        NavRequest.readiness(ReadinessMode.ONBOARDING));
            case DISCLAIMER:
            case HOME:
            default:
                return List.of();
        }
    }

    /**
     * Returns the root start a step establishes: a graph target that pops another graph inclusively (S-07 „Done” →
     * {@code home_graph} replacing {@code onboarding_graph}) becomes the bottom of the back stack, so the root start
     * must follow it (otherwise Up on S-01 would relaunch the task into onboarding).
     *
     * @param request the executed step
     * @return the new root start, or {@code null} when the step keeps it
     */
    public static NavTarget rootStartAfter(NavRequest request) {
        Objects.requireNonNull(request, "request");
        boolean graph = request.target() == NavTarget.HOME || request.target() == NavTarget.ONBOARDING;
        boolean replacesGraph = request.popInclusive()
                && (request.popUpTo() == NavTarget.HOME || request.popUpTo() == NavTarget.ONBOARDING);
        return graph && replacesGraph ? request.target() : null;
    }

    /**
     * Value saved with the activity state for the current root start.
     *
     * @param start HOME or ONBOARDING
     * @return the value to save
     */
    public static String saveStart(NavTarget start) {
        if (start != NavTarget.HOME && start != NavTarget.ONBOARDING) {
            throw new IllegalArgumentException("root start must be a graph: " + start);
        }
        return start.name();
    }

    /**
     * Parses the saved root start; anything but a saved HOME / ONBOARDING means „no graph was set” (resolve again).
     *
     * @param saved the saved value; nullable
     * @return HOME, ONBOARDING or {@code null}
     */
    public static NavTarget restoredStart(String saved) {
        if (NavTarget.HOME.name().equals(saved)) {
            return NavTarget.HOME;
        }
        if (NavTarget.ONBOARDING.name().equals(saved)) {
            return NavTarget.ONBOARDING;
        }
        return null;
    }
}
