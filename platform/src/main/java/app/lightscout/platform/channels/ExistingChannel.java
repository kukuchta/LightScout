package app.lightscout.platform.channels;

/**
 * A channel as currently registered in the system.
 *
 * @param id         system channel id
 * @param importance current importance (may be changed by the user)
 * @param bypassDnd  {@code canBypassDnd()}
 */
public record ExistingChannel(String id, int importance, boolean bypassDnd) {
}
