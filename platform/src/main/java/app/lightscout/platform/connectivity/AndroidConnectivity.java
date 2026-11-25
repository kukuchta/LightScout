package app.lightscout.platform.connectivity;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.function.Consumer;

import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.Logger;

/**
 * IF-19 {@link Connectivity} backed by a {@code ConnectivityManager} default-network {@code NetworkCallback}
 * (ADR-052, SR-23: no broadcast receiver). {@link #isOnline()} reflects whether the default network has
 * <em>validated</em> internet (C-5).
 *
 * <p>The callback is registered by the constructor and kept for the process lifetime. Create exactly <b>one</b>
 * instance per process (a singleton in the {@code :app} container), and only <b>after unlock</b> (CP-00A R-37;
 * Architect ruling on SS-07): every instance registers its own callback, and nothing unregisters it.
 * Listener callbacks fire on the system's connectivity thread, a background thread (CP-05 §8.5).
 *
 * <p>Without {@code ACCESS_NETWORK_STATE} (PERM-02) the state cannot be read; the port then reports online, so a
 * failure is classified {@code NS_NOT_RESPONDING} instead of {@code NO_INTERNET} (CP-05 §8.5).
 */
public final class AndroidConnectivity implements Connectivity {

    private final OnlineStateTracker tracker;

    /**
     * Reads the current state and registers the default-network callback.
     *
     * @param context any context (the application context is used)
     * @param logger  IF-66 logger ({@code NET_LOST}, {@code NET_REGAINED})
     */
    public AndroidConnectivity(Context context, Logger logger) {
        ConnectivityManager manager = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        this.tracker = new OnlineStateTracker(readInitial(manager), logger);
        register(manager);
    }

    @Override
    public boolean isOnline() {
        return tracker.isOnline();
    }

    @Override
    public void addListener(Consumer<Boolean> l) {
        tracker.addListener(l);
    }

    private static boolean readInitial(ConnectivityManager manager) {
        if (manager == null) {
            return true;
        }
        try {
            Network active = manager.getActiveNetwork();
            return active != null && hasValidatedInternet(manager.getNetworkCapabilities(active));
        } catch (SecurityException e) {
            return true; // PERM-02 missing: cause not distinguishable (CP-05 §8.5)
        }
    }

    private void register(ConnectivityManager manager) {
        if (manager == null) {
            return;
        }
        try {
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    tracker.update(hasValidatedInternet(capabilities));
                }

                @Override
                public void onLost(Network network) {
                    tracker.update(false);
                }
            });
        } catch (SecurityException e) {
            tracker.update(true); // PERM-02 missing: never report offline
        } catch (RuntimeException e) {
            // TooManyRequestsException etc.: keep the initial state; isOnline() stays usable.
        }
    }

    private static boolean hasValidatedInternet(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
