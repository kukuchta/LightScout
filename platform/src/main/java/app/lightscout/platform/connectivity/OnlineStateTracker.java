package app.lightscout.platform.connectivity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * Holds the validated-internet state of the default network and notifies listeners on changes only
 * (CP-05 §8.5). Logs {@code NET_LOST} / {@code NET_REGAINED} on each transition (CP-00A R-13).
 *
 * <p>Listeners run on the thread that reports the change (the {@code ConnectivityManager} callback thread);
 * consumers hop executors themselves (CP-00 §4). A failing listener does not stop the others.
 */
final class OnlineStateTracker {

    private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    private final Logger logger;
    private volatile boolean online;

    /**
     * Creates the tracker.
     *
     * @param initiallyOnline the state read once at start
     * @param logger          IF-66 logger
     */
    OnlineStateTracker(boolean initiallyOnline, Logger logger) {
        this.online = initiallyOnline;
        this.logger = logger;
    }

    /**
     * Returns the current state.
     *
     * @return {@code true} when the default network has validated internet
     */
    boolean isOnline() {
        return online;
    }

    /**
     * Registers a listener.
     *
     * @param listener receives the new state on each change
     */
    void addListener(Consumer<Boolean> listener) {
        listeners.add(listener);
    }

    /**
     * Applies a new state; notifies listeners and logs only when it differs from the current one.
     *
     * @param nowOnline the new state
     */
    synchronized void update(boolean nowOnline) {
        if (nowOnline == online) {
            return;
        }
        online = nowOnline;
        logger.event(nowOnline ? EventCode.NET_REGAINED : EventCode.NET_LOST);
        for (Consumer<Boolean> listener : listeners) {
            try {
                listener.accept(nowOnline);
            } catch (RuntimeException ignored) {
                // A faulty listener must not break connectivity delivery to the others (SR-37).
            }
        }
    }
}
