package app.lightscout;

import java.util.Collections;
import java.util.Set;

/**
 * What {@code MainActivity}'s SR-21 clean intent keeps of an incoming intent (BUG-05 / Q-94, BUG-08 / Q-95).
 * Android-free, so the rule is JVM-tested without Robolectric.
 *
 * <p>The clean intent always names only this activity's component. Of the incoming intent it keeps
 * {@code ACTION_MAIN}, and {@code CATEGORY_LAUNCHER} together with it, only when the incoming intent has them: they
 * carry no payload, and keeping them lets a plain launcher start (and {@code ActivityScenario}, which matches the
 * activity by action, data, type, component and categories) still recognise the activity. Data, type, extras, clip
 * data, the selector, every other action and every other category are dropped: the rule only ever reads the action
 * and the categories, so nothing else can reach the clean intent.
 */
final class CleanLaunch {

    /** {@code Intent.ACTION_MAIN}. */
    static final String ACTION_MAIN = "android.intent.action.MAIN";

    /** {@code Intent.CATEGORY_LAUNCHER}. */
    static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";

    private static final CleanLaunch COMPONENT_ONLY = new CleanLaunch(null, Collections.emptySet());
    private static final CleanLaunch MAIN = new CleanLaunch(ACTION_MAIN, Collections.emptySet());
    private static final CleanLaunch MAIN_LAUNCHER =
            new CleanLaunch(ACTION_MAIN, Collections.singleton(CATEGORY_LAUNCHER));

    private final String action;
    private final Set<String> categories;

    private CleanLaunch(String action, Set<String> categories) {
        this.action = action;
        this.categories = categories;
    }

    /**
     * Applies the rule to the action and categories of an incoming intent.
     *
     * @param action the incoming action, or {@code null}
     * @param categories the incoming categories, or {@code null}
     * @return what the clean intent keeps besides the component
     */
    static CleanLaunch of(String action, Set<String> categories) {
        if (!ACTION_MAIN.equals(action)) {
            return COMPONENT_ONLY; // no MAIN: component only (a LAUNCHER category alone is dropped too)
        }
        return categories != null && categories.contains(CATEGORY_LAUNCHER) ? MAIN_LAUNCHER : MAIN;
    }

    /**
     * Returns the action to set on the clean intent.
     *
     * @return {@code ACTION_MAIN} or {@code null} (no action)
     */
    String action() {
        return action;
    }

    /**
     * Returns the categories to add to the clean intent.
     *
     * @return empty or exactly {@code CATEGORY_LAUNCHER} (unmodifiable)
     */
    Set<String> categories() {
        return categories;
    }
}
