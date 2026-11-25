package app.lightscout.platform.log;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import app.lightscout.domain.port.EventCode;

/**
 * Formats and parses the log line of CP-00A R-13: {@code <ISO-8601 UTC> <LEVEL> <CODE>[:<detail>]}.
 * Used for both the release logcat line and the ring buffer.
 */
public final class LogLine {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private LogLine() {
    }

    /**
     * Formats a line.
     *
     * @param time       event time
     * @param code       event code (its level is written)
     * @param safeDetail an already redacted detail, or {@code null}
     * @return the line without a line terminator
     */
    public static String format(Instant time, EventCode code, String safeDetail) {
        StringBuilder line = new StringBuilder(64)
                .append(FORMAT.format(time))
                .append(' ')
                .append(code.level().name())
                .append(' ')
                .append(code.name());
        if (safeDetail != null) {
            line.append(':').append(safeDetail);
        }
        return line.toString();
    }

    /**
     * Parses the timestamp at the start of a line.
     *
     * @param line a line written by {@link #format}
     * @return the timestamp, or {@code null} when the line is not well formed
     */
    public static Instant timestamp(String line) {
        int space = line.indexOf(' ');
        if (space <= 0) {
            return null;
        }
        try {
            return Instant.parse(line.substring(0, space));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
