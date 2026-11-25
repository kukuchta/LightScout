package app.lightscout.domain.port;

import app.lightscout.domain.status.StatusSnapshot;

/**
 * Status snapshot listener (IF-18). Fires on {@code monitorExecutor}.
 */
public interface StatusListener {

    /**
     * Receives a new snapshot.
     *
     * @param s the snapshot
     */
    void onStatus(StatusSnapshot s);
}
