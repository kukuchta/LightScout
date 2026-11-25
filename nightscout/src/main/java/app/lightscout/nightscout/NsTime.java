package app.lightscout.nightscout;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Time formats used on the wire (T-1: all instants are UTC epoch ms internally).
 */
final class NsTime {

    /** ISO 8601 UTC with milliseconds, e.g. {@code 2026-10-13T08:15:00.000Z} (05 §3 {@code created_at}, IF-35). */
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** RFC 7231 IMF-fixdate, e.g. {@code Tue, 14 Nov 2023 22:13:21 GMT} (IF-40). */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private NsTime() {
    }

    /**
     * Formats an instant as ISO 8601 UTC with milliseconds. A fixed width keeps lexicographic order equal to time
     * order for {@code created_at} range queries.
     *
     * @param t the instant
     * @return the text
     */
    static String iso(Instant t) {
        return ISO_MILLIS.format(t);
    }

    /**
     * Parses an ISO 8601 date-time with {@code Z} or an offset.
     *
     * @param text the text, may be {@code null}
     * @return the instant, or {@code null} if absent or unparseable
     */
    static Instant parseIso(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /**
     * {@code If-Unmodified-Since} for IF-40 (CP-00A R-40): {@code ceil(srvModifiedMs / 1000)} seconds as HTTP-date;
     * an exact second is kept — never a false 412 (VR-02).
     *
     * @param srvModifiedMs server modification time in epoch ms
     * @return the HTTP-date
     */
    static String ifUnmodifiedSince(long srvModifiedMs) {
        long seconds = Math.floorDiv(srvModifiedMs, 1000L) + (Math.floorMod(srvModifiedMs, 1000L) == 0 ? 0 : 1);
        return HTTP_DATE.format(Instant.ofEpochSecond(seconds));
    }

    /**
     * Phone UTC offset in minutes at an instant (05 §3 {@code utcOffset}).
     *
     * @param zone phone zone (IF-15)
     * @param t    event time
     * @return offset in minutes
     */
    static int utcOffsetMinutes(ZoneId zone, Instant t) {
        return zone.getRules().getOffset(t).getTotalSeconds() / 60;
    }
}
