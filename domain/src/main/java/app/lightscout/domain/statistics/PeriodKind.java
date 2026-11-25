package app.lightscout.domain.statistics;

/**
 * Statistics periods (design S-03, S-01 doughnut, CP-00A R-7).
 */
public enum PeriodKind {
    /** Previous local calendar day 00:00–24:00 (23 h / 25 h on DST days). */
    YESTERDAY,
    /** The 24 hours ending now (S-01 doughnut). */
    LAST_24_HOURS,
    /** The 7 days ending now. */
    LAST_7_DAYS,
    /** The 14 days ending now. */
    LAST_14_DAYS,
    /** The 30 days ending now. */
    LAST_30_DAYS
}
