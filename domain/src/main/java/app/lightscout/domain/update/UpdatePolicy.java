package app.lightscout.domain.update;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import app.lightscout.domain.support.SupportPromptPolicy;

/**
 * Pure rules of the update check (DCR-49): when to ask GitHub and when the dialog may appear. No state: the caller
 * supplies the persisted values.
 *
 * <p>The dialog may appear when all hold: the check is on; this build is a stable release; a stable version newer
 * than this one is known and was not offered before (a version newer than the last offered one counts again); the
 * local time is in the daily window of the support prompt, {@code [09:00, 20:00)}; the caller reports a calm moment.
 */
public final class UpdatePolicy {

    /** Minimum time between two checks of GitHub. */
    public static final Duration CHECK_INTERVAL = Duration.ofHours(24);

    /** Creates the (stateless) policy. */
    public UpdatePolicy() {
        // no state
    }

    /**
     * Returns whether GitHub should be asked now.
     *
     * @param now       current instant
     * @param lastCheck when GitHub was last asked; {@code null} = never
     * @param enabled   the user keeps the check on
     * @param current   this build's version; {@code null} = not a stable release (nightly, debug), never checks
     * @return {@code true} when a check is due
     */
    public boolean isCheckDue(Instant now, Instant lastCheck, boolean enabled, AppVersion current) {
        if (!enabled || current == null) {
            return false;
        }
        if (lastCheck == null) {
            return true;
        }
        Duration since = Duration.between(lastCheck, now);
        return since.isNegative() || since.compareTo(CHECK_INTERVAL) >= 0;   // negative: the clock went back
    }

    /**
     * Returns whether a newer version is known than the installed one.
     *
     * @param current this build's version; {@code null} = not a stable release
     * @param latest  newest known stable version; {@code null} = unknown
     * @return {@code true} when {@code latest} is newer than {@code current}
     */
    public boolean isNewer(AppVersion current, AppVersion latest) {
        return current != null && latest != null && latest.compareTo(current) > 0;
    }

    /**
     * Returns whether the dialog may appear now.
     *
     * @param now          current instant
     * @param zone         device time zone for the daily window
     * @param enabled      the user keeps the check on
     * @param calm         nothing ringing, no active snooze, no fault banner, a reading is shown
     * @param current      this build's version; {@code null} = not a stable release
     * @param latest       newest known stable version; {@code null} = unknown
     * @param lastPrompted newest version the dialog was shown for; {@code null} = never
     * @return {@code true} when the dialog may appear now
     */
    public boolean shouldShow(Instant now, ZoneId zone, boolean enabled, boolean calm, AppVersion current,
                              AppVersion latest, AppVersion lastPrompted) {
        if (!enabled || !calm || !isNewer(current, latest)) {
            return false;
        }
        if (lastPrompted != null && latest.compareTo(lastPrompted) <= 0) {
            return false;
        }
        LocalTime local = now.atZone(zone).toLocalTime();
        return !local.isBefore(SupportPromptPolicy.WINDOW_START) && local.isBefore(SupportPromptPolicy.WINDOW_END);
    }
}
