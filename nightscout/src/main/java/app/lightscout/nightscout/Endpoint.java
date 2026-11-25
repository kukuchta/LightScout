package app.lightscout.nightscout;

/**
 * Name of a Nightscout call for the debug timing line (IF-number + endpoint name). Never the URL, host, query or
 * token (SR-07, C-9): the label is a compile-time constant, so a debug line cannot carry request data.
 */
enum Endpoint {
    /** IF-30 JWT request (the access token is in the path, so only this name is ever logged). */
    IF30_JWT("IF-30 jwt"),
    /** IF-31 version (no token). */
    IF31_VERSION("IF-31 version"),
    /** IF-33 lastModified. */
    IF33_LAST_MODIFIED("IF-33 lastModified"),
    /** IF-34 readings ({@code type=sgv}), incremental or window. */
    IF34_SGV("IF-34 sgv"),
    /** IF-34 meter BG ({@code type=mbg}), incremental or window. */
    IF34_MBG("IF-34 mbg"),
    /** IF-35 treatments window. */
    IF35_TREATMENTS("IF-35 treatments"),
    /** IF-36 device status. */
    IF36_DEVICESTATUS("IF-36 devicestatus"),
    /** IF-36 latest profile. */
    IF36_PROFILE("IF-36 profile"),
    /** IF-37 history. */
    IF37_HISTORY("IF-37 history"),
    /** IF-38 create. */
    IF38_CREATE("IF-38 create"),
    /** IF-39 read one entry. */
    IF39_GET("IF-39 get"),
    /** IF-40 update. */
    IF40_UPDATE("IF-40 update"),
    /** IF-41 delete. */
    IF41_DELETE("IF-41 delete");

    private final String label;

    Endpoint(String label) {
        this.label = label;
    }

    /**
     * Returns the log label, {@code IF-<n> <name>}.
     *
     * @return the label
     */
    String label() {
        return label;
    }
}
