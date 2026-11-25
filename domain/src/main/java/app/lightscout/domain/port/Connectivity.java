package app.lightscout.domain.port;

import java.util.function.Consumer;

/**
 * Network state port (IF-19), implemented by {@code :platform}.
 */
public interface Connectivity {

    /**
     * Tells whether a validated network is available.
     *
     * @return {@code true} when online
     */
    boolean isOnline();

    /**
     * Registers a listener for online changes.
     *
     * @param l receives the new online state
     */
    void addListener(Consumer<Boolean> l);
}
