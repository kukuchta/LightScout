package app.lightscout.platform.channels;

import android.app.Notification;
import android.app.NotificationManager;

import app.lightscout.platform.ChannelId;
import app.lightscout.platform.R;

/**
 * Channel properties of CP-05 §8.1 (06 §3, §10; SR-24, ADR-059, D-024; DCR-06 / D-061). The table is fixed: no other
 * channels and no changed ids, names, importance or visibility (CP-05 §12).
 *
 * <p><b>CH-02 (DCR-06 / D-061, BUG-16).</b> „Monitoring” is IMPORTANCE_DEFAULT with no channel sound and no vibration
 * ({@link #silent()}), so OEMs that hide <i>silent</i> (LOW) notifications on the lock screen (HyperOS) still show
 * S-10 and „LightScout is paused”; it is never audible and never peeks (heads-up needs HIGH). Importance cannot be
 * changed on an existing channel, so the id moved from {@code monitoring} (LOW, up to BUG-16) to
 * {@code monitoring_v1}; {@link DefaultNotificationChannels#ensureChannels()} deletes the old one.
 *
 * <table>
 *   <caption>CP-05 §8.1</caption>
 *   <tr><th>CH</th><th>id</th><th>importance</th><th>DND bypass</th><th>lock screen</th><th>sound</th></tr>
 *   <tr><td>CH-01</td><td>alarms (versioned, F7)</td><td>HIGH</td><td>yes</td><td>PUBLIC</td>
 *       <td>silent (AlarmPlayer)</td></tr>
 *   <tr><td>CH-02</td><td>monitoring_v1 (was monitoring)</td><td>DEFAULT</td><td>no</td><td>PUBLIC</td>
 *       <td>silent (no sound, no vibration)</td></tr>
 *   <tr><td>CH-03</td><td>reminders</td><td>DEFAULT</td><td>no</td><td>PRIVATE</td><td>default</td></tr>
 *   <tr><td>CH-04</td><td>pump_alerts</td><td>DEFAULT</td><td>no</td><td>PRIVATE</td><td>default</td></tr>
 * </table>
 *
 * @param channel              the channel
 * @param baseId               the system channel id ({@code alarms} is the base of {@code alarms_v{n}}; CH-02 is
 *                             {@code monitoring_v1})
 * @param nameRes              string resource of the channel name (CON-08)
 * @param importance           {@code NotificationManager.IMPORTANCE_*}
 * @param bypassDnd            whether the channel asks to bypass DND (honoured only with PERM-10)
 * @param lockscreenVisibility {@code Notification.VISIBILITY_*}
 * @param silent               {@code true}: no channel sound and no channel vibration
 */
public record ChannelSpec(ChannelId channel, String baseId, int nameRes, int importance, boolean bypassDnd,
                          int lockscreenVisibility, boolean silent) {

    /** Base id of CH-01. */
    public static final String ALARMS_BASE_ID = "alarms";

    /** Current id of CH-02 (DCR-06: IMPORTANCE_DEFAULT, silent). */
    public static final String MONITORING_ID = "monitoring_v1";

    /** Id of CH-02 up to BUG-16 (IMPORTANCE_LOW); deleted by {@code ensureChannels()} when present. */
    public static final String MONITORING_LEGACY_ID = "monitoring";

    /**
     * Returns the fixed spec of a channel.
     *
     * @param id the channel
     * @return its spec
     */
    public static ChannelSpec of(ChannelId id) {
        return switch (id) {
            case ALARMS -> new ChannelSpec(id, ALARMS_BASE_ID, R.string.s11_channel_alarms,
                    NotificationManager.IMPORTANCE_HIGH, true, Notification.VISIBILITY_PUBLIC, true);
            case MONITORING -> new ChannelSpec(id, MONITORING_ID, R.string.s10_channel_monitoring,
                    NotificationManager.IMPORTANCE_DEFAULT, false, Notification.VISIBILITY_PUBLIC, true);
            case REMINDERS -> new ChannelSpec(id, "reminders", R.string.s11_channel_reminders,
                    NotificationManager.IMPORTANCE_DEFAULT, false, Notification.VISIBILITY_PUBLIC, false);
            case PUMP_ALERTS -> new ChannelSpec(id, "pump_alerts", R.string.s11_channel_pump_alerts,
                    NotificationManager.IMPORTANCE_DEFAULT, false, Notification.VISIBILITY_PUBLIC, false);
        };
    }
}
