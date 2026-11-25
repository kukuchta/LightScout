package app.lightscout.ui.core;

import java.time.Duration;

/**
 * Pure reading-age and consumable-age rules (CP-00A R-82, T-3), used by {@link Formatters}.
 */
final class AgeText {

    /** Which reading-age template applies. */
    enum Kind {
        /** Age below 60 s (negative ages count as 0): {@code s01_age_just_now}. */
        JUST_NOW,
        /** 1 to 59 min (floor): {@code s01_age_min}. */
        MINUTES,
        /** From 60 min (floor): {@code s01_age_h_min}. */
        HOURS_MINUTES
    }

    /**
     * Reading age split into the template and its values.
     *
     * @param kind    template
     * @param hours   whole hours (HOURS_MINUTES only, else 0)
     * @param minutes whole minutes (MINUTES: total minutes; HOURS_MINUTES: minutes within the hour)
     */
    record Parts(Kind kind, long hours, long minutes) {
    }

    private AgeText() {
    }

    /**
     * Splits a reading age (S-01 C-S01-10): "Just now" below 60 s, "{n} min ago" for 1 to 59 min,
     * "{h} h {m} min ago" from 60 min; floor; negative counts as 0.
     *
     * @param age non-null age
     * @return the parts
     */
    static Parts reading(Duration age) {
        long totalMinutes = totalMinutes(age);
        if (totalMinutes < 1) {
            return new Parts(Kind.JUST_NOW, 0, 0);
        }
        if (totalMinutes < 60) {
            return new Parts(Kind.MINUTES, 0, totalMinutes);
        }
        return new Parts(Kind.HOURS_MINUTES, totalMinutes / 60, totalMinutes % 60);
    }

    /**
     * Whole minutes of an age, floor, negative counts as 0 (S-12 "{n} min" uses the total, e.g. 75).
     *
     * @param age non-null age
     * @return minutes, at least 0
     */
    static long totalMinutes(Duration age) {
        if (age.isNegative()) {
            return 0;
        }
        return age.toMinutes();
    }

    /**
     * Consumable age "{d} d {h} h" (T-3): absolute duration between instants (DST-independent), floor to whole
     * hours, negative counts as 0.
     *
     * @param age non-null age
     * @return {@code [days, hours]}
     */
    static long[] daysHours(Duration age) {
        long hours = age.isNegative() ? 0 : age.toHours();
        return new long[] {hours / 24, hours % 24};
    }
}
