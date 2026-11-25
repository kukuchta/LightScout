package app.lightscout;

import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.StorageException;
import app.lightscout.usecases.connection.OriginBoundTokenSource;
import java.util.Objects;
import java.util.Optional;

/**
 * Start-up configuration of the <b>monitoring</b> gateway (SS-06 §1, Q-46, IR-45, IR-52): once per process,
 * {@code tokens.bind(storedUrl)} and then {@code monitoringGateway.configure(storedUrl, tokens)} with the one shared
 * {@link OriginBoundTokenSource}. Later re-binding is done by {@code ConnectionStateReset} on the monitor thread
 * (R-71).
 *
 * <p>It reads CE storage, so it never runs on the main thread, and it configures the monitoring gateway, so it runs
 * <b>only on the monitor thread</b> — the single writer that also runs the R-71 reset (Q-46, IR-118): as a task posted
 * to {@code monitorExecutor} right when the gateway is built, inline inside a tick ({@code Supplier<SyncCycle>}), or
 * submitted from an io task that then waits ({@code AppContainer.monitoringIo()}). Because the R-71 reset task can
 * only be posted after the gateway exists, the start-up configuration always precedes any reset, and a reset is never
 * overwritten by it.
 */
final class MonitoringConnection {

    private final NightscoutGateway gateway;
    private final OriginBoundTokenSource tokens;
    private final ConnectionStore connectionStore;
    private boolean configured;

    /**
     * Creates the start-up step.
     *
     * @param gateway         the monitoring gateway (IF-07)
     * @param tokens          the shared origin-bound token source over {@code connectionStore}
     * @param connectionStore IF-12
     */
    MonitoringConnection(NightscoutGateway gateway, OriginBoundTokenSource tokens, ConnectionStore connectionStore) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.connectionStore = Objects.requireNonNull(connectionStore, "connectionStore");
    }

    /**
     * Binds the token source to the stored server and configures the monitoring gateway — once. A storage failure
     * leaves it unconfigured (the store logs {@code STORAGE_ERROR}) and the next call retries. Never on the main
     * thread; only on the monitor thread.
     */
    synchronized void ensureConfigured() {
        if (configured) {
            return;
        }
        try {
            Optional<String> stored = connectionStore.baseUrl();
            String url = stored.orElse(null);
            tokens.bind(url);
            if (url != null) {
                gateway.configure(url, tokens);
            }
            configured = true;
        } catch (StorageException e) {
            // logged by the store (R-13); retried on the next call (no secret or value in any log, SR-07)
        }
    }

    /**
     * Tells whether the start-up configuration has completed (fast path for io callers).
     *
     * @return {@code true} once configured
     */
    synchronized boolean isConfigured() {
        return configured;
    }
}
