package app.lightscout.platform.channels;

import java.util.List;

/**
 * System seam of {@link DefaultNotificationChannels}: the subset of {@code NotificationManager} it needs.
 */
public interface ChannelBackend {

    /**
     * Lists this app's channels (deleted channels are not included).
     *
     * @return the channels
     */
    List<ExistingChannel> list();

    /**
     * Creates a channel with the given id and spec.
     *
     * @param id   system channel id
     * @param spec properties
     */
    void create(String id, ChannelSpec spec);

    /**
     * Deletes a channel.
     *
     * @param id system channel id
     */
    void delete(String id);

    /**
     * Tells whether notification-policy access (PERM-10) is granted. Only checked, never requested here (SR-26).
     *
     * @return {@code true} when granted
     */
    boolean notificationPolicyAccessGranted();
}
