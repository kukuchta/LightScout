package app.lightscout.monitor;

import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.status.TickReason;

import java.util.function.Consumer;

/**
 * WF-11 / ADR-052 runtime side: listens to IF-19 (the process-wide {@code NetworkCallback} owned by CMP-28,
 * CP-00A R-37) and requests an immediate tick when the network is regained. Loss needs no action here: the next
 * tick reports {@code NO_INTERNET} and {@code StatusRepository} re-projects {@code online} itself (A-08).
 */
final class ConnectivityTrigger implements Consumer<Boolean> {

    private final MonitorControl control;
    private boolean online;

    /**
     * Creates the trigger.
     *
     * @param control       IF-62
     * @param initialOnline the state at registration
     */
    ConnectivityTrigger(MonitorControl control, boolean initialOnline) {
        this.control = control;
        this.online = initialOnline;
    }

    @Override
    public synchronized void accept(Boolean nowOnline) {
        boolean value = Boolean.TRUE.equals(nowOnline);
        boolean regained = value && !online;
        online = value;
        if (regained) {
            control.requestTick(TickReason.CONNECTIVITY_REGAINED);
        }
    }
}
