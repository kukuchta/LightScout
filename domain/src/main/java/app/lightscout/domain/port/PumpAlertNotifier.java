package app.lightscout.domain.port;

import app.lightscout.domain.model.Entry;

/**
 * Pump-alert notification port (IF-17b, R-88), implemented by {@code :monitor}.
 */
public interface PumpAlertNotifier {

    /**
     * Posts a pump alert.
     *
     * @param alert the {@code PUMP_ALERT} entry
     */
    void post(Entry alert);
}
