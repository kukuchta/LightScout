package app.lightscout.alarm;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * DS:D-061: S-09 uses the dark theme from 22:00 to 07:00 local time regardless of the system setting; otherwise it
 * follows the system. CP-00A R-80: the window is re-checked every minute while S-09 is visible and S-09 is recreated
 * when a boundary passes.
 */
final class DarkWindow {

    /** Start of the dark window (inclusive). */
    static final LocalTime START = LocalTime.of(22, 0);

    /** End of the dark window (exclusive). */
    static final LocalTime END = LocalTime.of(7, 0);

    private DarkWindow() {
    }

    /**
     * Tells whether a local time is inside the dark window.
     *
     * @param t local time
     * @return {@code true} for 22:00 ≤ t &lt; 07:00
     */
    static boolean isDark(LocalTime t) {
        return !t.isBefore(START) || t.isBefore(END);
    }

    /**
     * Tells whether an instant is inside the dark window in a zone.
     *
     * @param now  the instant (IF-15)
     * @param zone the phone's current zone (IF-15)
     * @return {@code true} inside the window
     */
    static boolean isDark(Instant now, ZoneId zone) {
        return isDark(now.atZone(zone).toLocalTime());   // LocalTime.ofInstant is API 31+
    }
}
