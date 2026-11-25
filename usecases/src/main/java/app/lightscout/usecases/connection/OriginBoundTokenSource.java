package app.lightscout.usecases.connection;

import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.port.TokenSource;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Token source of the <b>monitoring</b> gateway, bound to one server identity (SS-06 IR-45; SR-13 / C-13, CP-03
 * C4-25). It hands out the stored token only while the stored base URL has the identity the gateway was configured
 * with. Between {@code ConnectionService.save} committing a new server and the R-71 task re-pointing the gateway, a
 * tick or entry write that needs a JWT therefore gets no token instead of sending the new server's token to the old
 * origin.
 *
 * <p>Composition root (CP-15): create one instance over the {@link ConnectionStore}; at start-up call
 * {@link #bind(String)} with the stored base URL before {@code gateway.configure(storedUrl, thisSource)}; pass the
 * same instance to {@link ConnectionStateReset}, which re-binds it in both R-71 paths.
 */
public final class OriginBoundTokenSource implements TokenSource {

    private final ConnectionStore store;
    private volatile String boundIdentity;

    /**
     * Creates the source, bound to nothing (no token until {@link #bind}).
     *
     * @param store IF-12
     */
    public OriginBoundTokenSource(ConnectionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Binds the source to the server identity of a base URL (R-71 normalisation).
     *
     * @param baseUrl base URL the monitoring gateway is configured with; nullable (= bound to nothing)
     */
    public void bind(String baseUrl) {
        boundIdentity = ConnectionInput.serverIdentity(baseUrl);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The identity is checked before and after the decryption, so a save that commits in between never lets the
     * new token out to the old origin. Store failures → empty (the gateway reports {@code TOKEN_MISSING}).
     */
    @Override
    public Optional<char[]> token() {
        String bound = boundIdentity;
        if (bound == null) {
            return Optional.empty();
        }
        try {
            if (!bound.equals(storedIdentity())) {
                return Optional.empty();
            }
            Optional<char[]> t = store.token();
            if (t.isPresent() && !bound.equals(storedIdentity())) {
                Arrays.fill(t.get(), '\0');
                return Optional.empty();
            }
            return t;
        } catch (StorageException e) {
            return Optional.empty();
        }
    }

    /**
     * Tells whether the monitoring gateway is configured for the stored server, i.e. no saved server change is still
     * waiting for its R-71 task (SS-06 IR-52: entry writes must not go to the old server).
     *
     * @return {@code true} when bound to the stored server's identity
     */
    public boolean boundToStoredServer() {
        String bound = boundIdentity;
        try {
            return bound != null && bound.equals(storedIdentity());
        } catch (StorageException e) {
            return false;
        }
    }

    private String storedIdentity() {
        return ConnectionInput.serverIdentity(store.baseUrl().orElse(null));
    }
}
