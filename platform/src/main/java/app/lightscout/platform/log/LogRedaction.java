package app.lightscout.platform.log;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * SR-07 redaction for the logger (CP-05 §8.6, CP-00A R-13). Works as an allow-list: a detail is written only when
 * every segment belongs to the fixed vocabulary of R-13; anything else is replaced by {@link #REDACTED} as a whole.
 *
 * <p>Allowed segments, separated by {@code :} or {@code >}:
 * <ul>
 *   <li>enum constant names and {@code DetailCode} values: {@code [A-Z]+(_[A-Z0-9]+)*} (e.g. {@code NO_INTERNET},
 *       {@code HTTP_5XX}, {@code AUTH_401});</li>
 *   <li>integer counts or durations in ms: up to 13 digits;</li>
 *   <li>store operation names of {@code :storage}: {@code <store>.<method>} where {@code <store>} is one of the
 *       store names in {@link #STORE_OPS} (a bare {@code word.word} could be a host, so it is not accepted).</li>
 * </ul>
 * Tokens, JWTs, {@code Authorization} values, auth paths, URLs, hosts, query strings, bodies, glucose / insulin /
 * carb values with units and free text therefore never reach logcat or the ring buffer.
 *
 * <p><b>Store-op vocabulary (Q-51).</b> {@link #STORE_OPS} is the closed list of store prefixes and is owned by
 * {@code :platform} (CMP-28). A {@code STORAGE_ERROR} op with any other prefix is written as {@link #REDACTED};
 * a new store prefix is added here, not in the caller. Exception messages are never
 * accepted (they embed URLs — SR-07).
 */
public final class LogRedaction {

    /** Replacement for a detail outside the fixed vocabulary. */
    public static final String REDACTED = "REDACTED";

    /** Store prefixes of the {@code STORAGE_ERROR:<op>} vocabulary used by {@code :storage} (R-20). */
    static final Set<String> STORE_OPS = Set.of(
            "alarm_state", "boot", "cache", "connection", "device_status", "entries", "flags", "monitor_state",
            "notified_alert", "profile", "readings", "reminder_state", "settings");

    private static final int MAX_DETAIL_LENGTH = 96;
    private static final int MAX_NAME_LENGTH = 128;
    private static final Pattern SEPARATORS = Pattern.compile("[:>]");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z]+(_[A-Z0-9]+)*");
    private static final Pattern INTEGER = Pattern.compile("[0-9]{1,13}");
    private static final Pattern STORE_OP = Pattern.compile("([a-z_]+)\\.[a-z][A-Za-z]{0,39}");
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.<>]*");

    private LogRedaction() {
    }

    /**
     * Returns the detail if it belongs to the fixed vocabulary, else {@link #REDACTED}.
     *
     * @param detail the caller's detail; may be {@code null}
     * @return the safe detail, or {@code null} when {@code detail} is {@code null} or empty
     */
    public static String detail(String detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        if (detail.length() > MAX_DETAIL_LENGTH) {
            return REDACTED;
        }
        if (detail.startsWith(":") || detail.startsWith(">") || detail.endsWith(":") || detail.endsWith(">")) {
            return REDACTED;
        }
        for (String segment : SEPARATORS.split(detail, -1)) {
            if (!allowedSegment(segment)) {
                return REDACTED;
            }
        }
        return detail;
    }

    /**
     * Returns a Java class or {@code Class.method} name for the crash line, or {@link #REDACTED} when it contains
     * anything else (no message text can pass).
     *
     * @param name the name; may be {@code null}
     * @return the safe name
     */
    public static String javaName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH || !JAVA_NAME.matcher(name).matches()) {
            return REDACTED;
        }
        return name;
    }

    private static boolean allowedSegment(String segment) {
        if (CONSTANT.matcher(segment).matches() || INTEGER.matcher(segment).matches()) {
            return true;
        }
        java.util.regex.Matcher op = STORE_OP.matcher(segment);
        return op.matches() && STORE_OPS.contains(op.group(1));
    }
}
