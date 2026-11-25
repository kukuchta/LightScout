package app.lightscout.ui.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import app.lightscout.domain.statistics.PeriodKind;

/**
 * Pure time-label rules (CP-00A R-82, R-83; T-1, T-2): local dates are decided in the given (current) zone; the
 * templates and patterns are passed in from resources by {@link Formatters}.
 */
final class TimeLabels {

    /** Relative day of an instant. */
    enum Day {
        /** Same local date as now. */
        TODAY,
        /** The previous local date (00:00 to 24:00; 23 h or 25 h on DST days). */
        YESTERDAY,
        /** Any other date. */
        OTHER
    }

    private TimeLabels() {
    }

    /**
     * Decides Today / Yesterday / other by local date in {@code zone}.
     *
     * @param t    the instant shown
     * @param now  current instant
     * @param zone current zone
     * @return the relative day
     */
    static Day day(Instant t, Instant now, ZoneId zone) {
        LocalDate date = t.atZone(zone).toLocalDate();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (date.equals(today)) {
            return Day.TODAY;
        }
        if (date.equals(today.minusDays(1))) {
            return Day.YESTERDAY;
        }
        return Day.OTHER;
    }

    /** Fallback clock pattern for the 24-hour setting (IR-61). */
    static final String FALLBACK_24H = "H:mm";
    /** Fallback clock pattern for the 12-hour setting (IR-61). */
    static final String FALLBACK_12H = "h:mm a";

    /**
     * Returns the platform's best clock pattern if {@code java.time} accepts it, otherwise the fallback for the
     * 12/24-hour setting (IR-61: ICU patterns may contain letters such as {@code B}, flexible day period, that
     * {@code DateTimeFormatter} rejects below API 34). Keeps {@link Formatters#timeLabel} and
     * {@link Formatters#clockTime} from throwing.
     *
     * @param bestPattern pattern from {@code DateFormat.getBestDateTimePattern}, may be {@code null}
     * @param is24        system 24-hour setting
     * @param locale      locale
     * @return a pattern that formats without exception
     */
    static String safeClockPattern(String bestPattern, boolean is24, Locale locale) {
        if (bestPattern != null && !bestPattern.isEmpty()) {
            try {
                DateTimeFormatter.ofPattern(bestPattern, locale).withZone(ZoneId.of("UTC")).format(Instant.EPOCH);
                return bestPattern;
            } catch (RuntimeException e) {
                // Pattern not supported by java.time on this API level: use the fallback below.
            }
        }
        return is24 ? FALLBACK_24H : FALLBACK_12H;
    }

    /**
     * Formats an instant with a pattern in a zone.
     *
     * @param t       instant
     * @param zone    zone
     * @param pattern {@link DateTimeFormatter} pattern
     * @param locale  locale of the resource configuration
     * @return the text
     */
    static String format(Instant t, ZoneId zone, String pattern, Locale locale) {
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(t);
    }

    /**
     * "Today 14:32" / "Yesterday 21:10" / "Mon 12 Oct 08:05".
     *
     * @param t                 instant shown
     * @param now               current instant
     * @param zone              current zone
     * @param locale            locale
     * @param timePattern       clock pattern (system 12/24 h)
     * @param todayTemplate     {@code fmt_time_today}
     * @param yesterdayTemplate {@code fmt_time_yesterday}
     * @param dateTemplate      {@code fmt_time_date}
     * @param dayPattern        {@code pattern_day}
     * @return the label
     */
    static String label(Instant t, Instant now, ZoneId zone, Locale locale, String timePattern,
                        String todayTemplate, String yesterdayTemplate, String dateTemplate, String dayPattern) {
        String time = format(t, zone, timePattern, locale);
        switch (day(t, now, zone)) {
            case TODAY:
                return String.format(locale, todayTemplate, time);
            case YESTERDAY:
                return String.format(locale, yesterdayTemplate, time);
            default:
                return String.format(locale, dateTemplate, format(t, zone, dayPattern, locale), time);
        }
    }

    /**
     * S-03 period text (R-78): Yesterday gives {@code pattern_day} of its date ("Sun 11 Oct"); n days give
     * {@code fmt_period_range} of {@code pattern_day_month} from the local start date to the local date of the
     * last included instant of the (exclusive) end, i.e. today ("5 Oct – 12 Oct").
     *
     * @param kind            period kind
     * @param start           inclusive start
     * @param end             exclusive end
     * @param zone            current zone
     * @param locale          locale
     * @param dayPattern      {@code pattern_day}
     * @param dayMonthPattern {@code pattern_day_month}
     * @param rangeTemplate   {@code fmt_period_range}
     * @return the label
     */
    static String period(PeriodKind kind, Instant start, Instant end, ZoneId zone, Locale locale,
                         String dayPattern, String dayMonthPattern, String rangeTemplate) {
        if (kind == PeriodKind.YESTERDAY) {
            return format(start, zone, dayPattern, locale);
        }
        Instant lastIncluded = end.isAfter(start) ? end.minusNanos(1) : end;
        return String.format(locale, rangeTemplate, format(start, zone, dayMonthPattern, locale),
                format(lastIncluded, zone, dayMonthPattern, locale));
    }
}
