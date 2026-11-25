package app.lightscout.ui.core;

/**
 * Gives fragments access to IF-29 (CP-00A R-12). Implemented by {@code MainActivity} in {@code :app}; features
 * obtain it with {@code (NavigatorHost) requireActivity()}.
 */
public interface NavigatorHost {

    /**
     * Returns the navigator of the hosting activity.
     *
     * @return IF-29 {@link Navigator}
     */
    Navigator navigator();
}
