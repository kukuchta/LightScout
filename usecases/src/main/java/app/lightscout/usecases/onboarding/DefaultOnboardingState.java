package app.lightscout.usecases.onboarding;

import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.StartDestination;

import java.util.Objects;

/**
 * CMP-17 implementation of IF-27 (design §9.1, DS:D-058, 03 §6.8; CP-02 §8.9; CP-00A R-31, R-72).
 *
 * <p>„Valid connection” = URL and token stored (verification results are not persisted, R-72). The token is never
 * decrypted for this: {@code ConnectionStore.save} stores URL and token together, so a stored URL means a stored
 * token (Q-43, confirmed: atomic commit in CP-04). A CE flag read failure falls back to the DE flag
 * {@code monitoringConfigured} (true → HOME, else DISCLAIMER); a connection-store failure reads as „no connection”.
 * Nothing here throws.
 */
public final class DefaultOnboardingState implements OnboardingState {

    private final AppFlagsStore flags;
    private final ConnectionStore connection;
    private final MonitorControl monitorControl;

    /**
     * Creates the use case.
     *
     * @param flags          IF-60
     * @param connection     IF-12 (URL presence only; the token is never read)
     * @param monitorControl IF-62
     */
    public DefaultOnboardingState(AppFlagsStore flags, ConnectionStore connection, MonitorControl monitorControl) {
        this.flags = Objects.requireNonNull(flags, "flags");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.monitorControl = Objects.requireNonNull(monitorControl, "monitorControl");
    }

    /**
     * {@inheritDoc}
     *
     * <p>R-72: not accepted → DISCLAIMER; completed → HOME (even if the connection fails later — no automatic
     * redirect); stored connection → READINESS; else CONNECTION.
     */
    @Override
    public StartDestination resolve() {
        boolean accepted;
        boolean completed;
        try {
            accepted = flags.disclaimerAccepted();
            completed = flags.onboardingCompleted();
        } catch (StorageException e) {
            // Architect ruling on SS-06 A-4: a configured user is never pushed back to S-05 by a CE read failure;
            // the DE flag monitoringConfigured decides
            return monitoringConfigured() ? StartDestination.HOME : StartDestination.DISCLAIMER;
        }
        if (!accepted) {
            return StartDestination.DISCLAIMER;
        }
        if (completed) {
            return StartDestination.HOME;
        }
        return hasStoredConnection() ? StartDestination.READINESS : StartDestination.CONNECTION;
    }

    private boolean monitoringConfigured() {
        try {
            return flags.monitoringConfigured();
        } catch (StorageException e) {
            return false;
        }
    }

    @Override
    public void acceptDisclaimer() {
        try {
            flags.setDisclaimerAccepted();
        } catch (StorageException e) {
            // logged by the store (R-13); S-05 stays the start destination until it is persisted (DS:D-058)
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Monitoring is started even if a flag cannot be persisted: the user finished onboarding and monitoring
     * reliability comes first (D-003, SR-37).
     */
    @Override
    public void completeOnboarding() {
        try {
            flags.setOnboardingCompleted(true);
        } catch (StorageException e) {
            // logged by the store (R-13)
        }
        try {
            flags.setMonitoringConfigured(true);
        } catch (StorageException e) {
            // logged by the store (R-13)
        }
        monitorControl.startMonitoring();
        monitorControl.requestTick(TickReason.ONBOARDING_DONE);
    }

    private boolean hasStoredConnection() {
        try {
            return connection.baseUrl().isPresent();
        } catch (StorageException e) {
            return false;
        }
    }
}
