package app.lightscout.domain.statistics;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Statistics period boundaries (design S-03, T-2, DS:D-054; CP-00A R-7). The zone is always passed in, never cached.
 */
public final class PeriodFactory {

    private PeriodFactory() {
    }

    /**
     * Returns the previous local calendar day 00:00–24:00 in the zone of {@code now} (23 h or 25 h on DST days).
     *
     * @param now the current time in the phone's current zone
     * @return the {@link PeriodKind#YESTERDAY} period
     */
    public static Period yesterday(ZonedDateTime now) {
        ZoneId zone = now.getZone();
        LocalDate today = now.toLocalDate();
        Instant start = today.minusDays(1).atStartOfDay(zone).toInstant();
        Instant end = today.atStartOfDay(zone).toInstant();
        return new Period(PeriodKind.YESTERDAY, start, end);
    }

    /**
     * Returns the {@code days} × 24 h ending at {@code now}.
     *
     * @param days one of 1, 7, 14, 30 (1 → {@link PeriodKind#LAST_24_HOURS}, S-01 doughnut)
     * @param now  end of the period (exclusive)
     * @return the period
     * @throws IllegalArgumentException for any other number of days
     */
    public static Period lastDays(int days, Instant now) {
        PeriodKind kind = switch (days) {
            case 1 -> PeriodKind.LAST_24_HOURS;
            case 7 -> PeriodKind.LAST_7_DAYS;
            case 14 -> PeriodKind.LAST_14_DAYS;
            case 30 -> PeriodKind.LAST_30_DAYS;
            default -> throw new IllegalArgumentException("days must be 1, 7, 14 or 30: " + days);
        };
        return new Period(kind, now.minus(Duration.ofDays(days)), now);
    }
}
