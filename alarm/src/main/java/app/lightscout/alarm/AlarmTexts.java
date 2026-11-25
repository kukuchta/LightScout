package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.dataloss.DataLossCause;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure content rules of S-09 (§7.1) and the S-11 alarm kinds (§7.2, §7.4; CP-00A R-5, R-80, R-83): which string
 * resource, which colour role and which line kind an {@code AlarmView} gets. The Android rendering is
 * {@link AlarmTextRenderer}.
 */
final class AlarmTexts {

    /**
     * GIR-10 colour role of the C-S09-01 title (never colour alone: the title text names the alarm).
     */
    enum TitleStyle {
        /** Urgent low: {@code errorContainer} badge. */
        BADGE_ERROR_CONTAINER,
        /** Low, No data, Not running: {@code error}. */
        ERROR,
        /** High: extended {@code warning}. */
        WARNING
    }

    /**
     * S-11 catalogue row (§7.2).
     */
    enum NotificationKind {
        /** Glucose alarm (Urgent low, Low, High). */
        GLUCOSE,
        /** No data alarm. */
        NO_DATA,
        /** Watchdog alarm (LightScout is not running). */
        WATCHDOG
    }

    private AlarmTexts() {
    }

    /**
     * Returns the C-S09-01 title (also the {@code {type}} of {@code s11_alarm_title}).
     *
     * @param t alarm type
     * @return string resource id
     */
    static int titleRes(AlarmType t) {
        switch (t) {
            case URGENT_LOW:
                return R.string.s09_title_urgent_low;
            case LOW:
                return R.string.s09_title_low;
            case HIGH:
                return R.string.s09_title_high;
            case NO_DATA:
                return R.string.s09_title_no_data;
            default:
                return R.string.s09_title_not_running;
        }
    }

    /**
     * Returns the GIR-10 title colour role (M3 fix C-S09-01).
     *
     * @param t alarm type
     * @return the role
     */
    static TitleStyle titleStyle(AlarmType t) {
        switch (t) {
            case URGENT_LOW:
                return TitleStyle.BADGE_ERROR_CONTAINER;
            case HIGH:
                return TitleStyle.WARNING;
            default:
                return TitleStyle.ERROR;
        }
    }

    /**
     * Tells whether S-09 shows a reading (C-S09-02); No data and Not running show „—”.
     *
     * @param t alarm type
     * @return {@code true} for Urgent low, Low, High
     */
    static boolean showsReading(AlarmType t) {
        return t == AlarmType.URGENT_LOW || t == AlarmType.LOW || t == AlarmType.HIGH;
    }

    /**
     * Returns the S-11 kind of a type.
     *
     * @param t alarm type
     * @return the kind
     */
    static NotificationKind kind(AlarmType t) {
        if (t == AlarmType.NO_DATA) {
            return NotificationKind.NO_DATA;
        }
        if (t == AlarmType.NOT_RUNNING) {
            return NotificationKind.WATCHDOG;
        }
        return NotificationKind.GLUCOSE;
    }

    /**
     * Maps a data-loss cause to its cause line (DS:D-024 — the only three cause texts, CP-00A R-5).
     *
     * @param cause the cause, may be {@code null}
     * @return string resource id, or 0 when there is no cause
     */
    static int causeRes(DataLossCause cause) {
        if (cause == null) {
            return 0;
        }
        switch (cause) {
            case NO_INTERNET:
                return R.string.s09_cause_no_internet;
            case NS_NOT_RESPONDING:
                return R.string.s09_cause_ns_not_responding;
            default:
                return R.string.s09_cause_uploader;
        }
    }

    /**
     * Whole minutes from an anchor to now (floor; a future anchor counts as 0).
     *
     * @param since the anchor
     * @param now   now (IF-15)
     * @return minutes ≥ 0
     */
    static long minutesSince(Instant since, Instant now) {
        long minutes = Duration.between(since, now).toMinutes();
        return Math.max(0L, minutes);
    }

    /**
     * Reading age for the CMP-31 {@code readingAge} format.
     *
     * @param readingTime time of the reading, may be {@code null}
     * @param now         now (IF-15)
     * @return the age, or {@code null} when there is no reading
     */
    static Duration age(Instant readingTime, Instant now) {
        return readingTime == null ? null : Duration.between(readingTime, now);
    }
}
