package app.lightscout.monitor;

import android.app.Notification;

import app.lightscout.domain.status.StatusSnapshot;

/**
 * Seam for the S-10 ongoing notification (CP-00A R-81 „M1 sequencing”). CP-08 ships the M1 placeholder
 * ({@link PlaceholderStatusNotificationBuilder}); CP-09 replaces it with the full S-10 builder in
 * {@link StatusNotifications#builder}. Whatever it builds is used by {@code startForeground}, so it must use a
 * **resource small icon** whenever it cannot render the number icon (F4) and must never throw.
 */
public interface StatusNotificationBuilder {

    /**
     * Builds the S-10 notification (id {@link NotificationIds#STATUS} on CH-02).
     *
     * @param s the current snapshot; {@code null} before the first snapshot of the process is available
     * @return the notification
     */
    Notification build(StatusSnapshot s);

    /** Called after a successful {@code startForeground}; subsequent updates may use the dynamic number icon. */
    default void onForegroundStarted() {
        // Non-S-10 implementations do not need a foreground transition.
    }

    /**
     * Gives the builder the source of the S-10 chart (DCR-37); builders without a chart ignore it.
     *
     * @param chart the chart source
     */
    default void setChart(StatusChart chart) {
        // Non-S-10 implementations have no chart.
    }
}
