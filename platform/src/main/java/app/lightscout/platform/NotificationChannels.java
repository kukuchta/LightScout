package app.lightscout.platform;

/**
 * CMP-26: creates the notification channels CH-01…CH-04 and reports their state (CP-05 §8.1, CP-00A R-11).
 *
 * <p>All methods only talk to {@code NotificationManager}; they are safe before the first unlock (CP-00 §6) and on
 * any thread.
 */
public interface NotificationChannels {

    /**
     * Creates every missing channel with the CP-05 §8.1 properties. Existing channels are left untouched, so user
     * changes are kept. Also finishes the CH-02 re-creation of DCR-06: {@code monitoring_v1} is created and the old
     * {@code monitoring} channel is deleted when present. Idempotent. Call it before posting on CH-02.
     */
    void ensureChannels();

    /**
     * Returns the current state of a channel.
     *
     * @param id the channel
     * @return its state; {@code exists == false} when the channel is missing
     */
    ChannelState state(ChannelId id);

    /**
     * Returns the channel id to post on. For {@link ChannelId#ALARMS} this is the current versioned id
     * ({@code alarms} or {@code alarms_v{n}}), derived from the existing channels (CP-00A R-74). For
     * {@link ChannelId#MONITORING} it is {@code monitoring_v1} (DCR-06), or the old {@code monitoring} only while
     * {@link #ensureChannels()} has not yet run on an older install.
     *
     * @param id the channel
     * @return the system channel id
     */
    String channelId(ChannelId id);

    /**
     * F7 (CP-05 §8.1) with the Q-52 safety guard: re-creates CH-01 as {@code alarms_v1} (with DND bypass) and deletes
     * the old channel <b>only</b> when notification-policy access (PERM-10) is granted, the current alarm channel is
     * v0 (plain {@code alarms}, derived per R-74) and it reports {@code canBypassDnd() == false}. A channel of
     * version 1 or higher is never re-created, so {@code CHANNEL_RECREATED} happens at most once per install; if it
     * still reports no bypass, readiness item 7 shows „needs attention” (VR-27 fallback; the user's choice is
     * respected, SR-24). Called on S-07 resume and on every tick.
     *
     * <p><b>Callers must not invoke it while an alarm rings</b> (deleting the channel would cancel the alarm
     * notification); CP-08 / CP-14 own that guard.
     */
    void ensureAlarmChannelBypass();
}
