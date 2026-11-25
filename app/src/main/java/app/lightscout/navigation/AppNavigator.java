package app.lightscout.navigation;

import app.lightscout.domain.port.Logger;
import app.lightscout.domain.model.EntryType;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;
import java.util.Objects;

/**
 * IF-29 {@link Navigator} (CP-15 §4.1, CP-00A R-12, R-38): the cross-feature edges of design §9.3.
 *
 * <ul>
 *   <li>S-01 / S-04 → S-06 edit, S-07 check: by destination id inside {@code onboarding_graph} (the NavController
 *       adds the graph entry, so the S-06 ViewModel scoped to {@code onboarding_graph} works).</li>
 *   <li>S-07 „Done” → S-01: {@code home_graph} with {@code onboarding_graph} popped inclusively — the onboarding stack
 *       is cleared and back on S-01 exits the app (CP-15 §7.2).</li>
 *   <li>S-01 → S-02 create / edit: typed internal dialog route (R-95, SS-28).</li>
 *   <li>{@link #finishWithResult}: result into the entry below, then pop (S-06 edit „Save”, S-02 save / delete →
 *       caller + Snackbar).</li>
 *   <li>S-01 → S-03 / S-04 opens the corresponding feature graph.</li>
 * </ul>
 * Main thread only.
 */
public final class AppNavigator implements Navigator {

    private static final String TAG = "Navigator";

    private final NavPort port;
    private final Logger logger;

    /**
     * Creates the navigator.
     *
     * @param port   the activity's navigation adapter
     * @param logger IF-66 (debug lines only; no values)
     */
    public AppNavigator(NavPort port, Logger logger) {
        this.port = Objects.requireNonNull(port, "port");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** {@inheritDoc} Edit-only route; create uses {@link #openNewEntry}. */
    @Override
    public void openEntry(String identifier) {
        port.navigate(NavRequest.entry(identifier));
    }

    @Override
    public void openNewEntry(EntryType initialType) {
        port.navigate(NavRequest.newEntry(initialType));
    }

    /** {@inheritDoc} S-03 {@code stats_graph} is nested in the root graph. */
    @Override
    public void openStatistics() {
        port.navigate(new NavRequest(NavTarget.STATISTICS, null, null, null, false));
    }

    /** {@inheritDoc} S-04 {@code settings_graph} is nested in the root graph. */
    @Override
    public void openSettings() {
        port.navigate(new NavRequest(NavTarget.SETTINGS, null, null, null, false));
    }

    /** {@inheritDoc} */
    @Override
    public void openConnection(ConnectionMode mode) {
        port.navigate(NavRequest.connection(mode));
    }

    /** {@inheritDoc} */
    @Override
    public void openReadiness(ReadinessMode mode) {
        port.navigate(NavRequest.readiness(mode));
    }

    /** {@inheritDoc} */
    @Override
    public void finishOnboarding() {
        port.navigate(NavRequest.replacing(NavTarget.HOME, NavTarget.ONBOARDING));
    }

    /** {@inheritDoc} */
    @Override
    public void finishWithResult(UiMessage message) {
        port.finishWithResult(Objects.requireNonNull(message, "message"));
    }
}
