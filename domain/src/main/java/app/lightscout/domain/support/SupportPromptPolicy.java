package app.lightscout.domain.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Decides whether the one-time support prompt may be shown now (DCR-46, D-166). Pure rule, no state: the caller
 * supplies the persisted first-use instant and the persisted „already shown” flag.
 *
 * <p>All of these must hold: the prompt was not shown before; the app was first used at least {@link #MIN_USE} ago;
 * the local time is in {@code [09:00, 20:00)}; the caller reports a calm moment (nothing ringing, no snooze, no
 * fault banner, data flowing). Showing it once is the caller's job: it marks the prompt shown at the moment it
 * appears, whatever the user then does.
 */
public final class SupportPromptPolicy {

    /** How long the app must have been in use before the prompt may appear. */
    public static final Duration MIN_USE = Duration.ofDays(7);
    /** Start of the daily window, inclusive (local time). */
    public static final LocalTime WINDOW_START = LocalTime.of(9, 0);
    /** End of the daily window, exclusive (local time). */
    public static final LocalTime WINDOW_END = LocalTime.of(20, 0);

    /** Creates the (stateless) policy. */
    public SupportPromptPolicy() {
        // no state
    }

    /**
     * Returns whether the prompt may be shown now.
     *
     * @param now          current instant
     * @param zone         device time zone for the daily window
     * @param firstUse     when the app was first used; {@code null} = unknown, never shows
     * @param alreadyShown the prompt was shown before
     * @param calm         nothing ringing, no active snooze, no fault banner, a reading is shown
     * @return {@code true} when the prompt may be shown now
     */
    public boolean shouldShow(Instant now, ZoneId zone, Instant firstUse, boolean alreadyShown, boolean calm) {
        if (alreadyShown || !calm || firstUse == null) {
            return false;
        }
        if (now.isBefore(firstUse.plus(MIN_USE))) {
            return false;
        }
        LocalTime local = now.atZone(zone).toLocalTime();
        return !local.isBefore(WINDOW_START) && local.isBefore(WINDOW_END);
    }
}
