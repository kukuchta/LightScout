package app.lightscout.domain.port;

/**
 * Logging port (IF-66, CP-00A R-13), implemented by {@code :platform}. {@code :domain} itself never logs (SR-07).
 *
 * <p>While the device is locked the implementation is a no-op for the CE ring buffer and never throws.
 */
public interface Logger {

    /**
     * Logs an event without detail.
     *
     * @param code the event code
     */
    void event(EventCode code);

    /**
     * Logs an event with a detail from the fixed vocabulary only: enum constant names, {@link DetailCode} values,
     * integer counts or durations in ms. Never values, host, URL, token or free text (SR-07).
     *
     * @param code   the event code
     * @param detail the detail
     */
    void event(EventCode code, String detail);

    /**
     * Records a crash as {@code CRASH:<SimpleClass>@<Class.method>}, without the message.
     *
     * @param exceptionClass simple class name of the exception
     * @param topAppFrame    top application frame as {@code Class.method}
     */
    void crash(String exceptionClass, String topAppFrame);

    /**
     * Debug output; debug builds only, no-op in release.
     *
     * @param tag     tag
     * @param message message
     */
    void debug(String tag, String message);
}
