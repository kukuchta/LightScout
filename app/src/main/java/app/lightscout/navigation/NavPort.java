package app.lightscout.navigation;

import app.lightscout.ui.core.UiMessage;

/**
 * What {@link AppNavigator} needs from the hosting activity's {@code NavController} (implemented by
 * {@link NavControllerPort}; a fake in JVM tests).
 */
public interface NavPort {

    /**
     * Executes one navigation step. A step that cannot be executed throws in debug builds and is a no-op in release
     * (no log line, R-13; BUG-05 / Q-94).
     *
     * @param request the step
     */
    void navigate(NavRequest request);

    /**
     * Puts {@code message} into the previous back-stack entry's {@code SavedStateHandle} under
     * {@code NavResults.KEY} and pops the current destination (CP-00A R-38, SS-08 A-10: the enum itself).
     *
     * @param message the result for the destination below
     */
    void finishWithResult(UiMessage message);
}
