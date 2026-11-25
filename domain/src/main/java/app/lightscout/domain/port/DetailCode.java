package app.lightscout.domain.port;

/**
 * {@link Result#detailCode()} catalogue (CP-00A R-50). Fixed vocabulary; safe for logs (SR-07).
 */
public final class DetailCode {

    /** {@link FetchOutcome#NO_INTERNET}: DNS / connect failure without a validated network. */
    public static final String NO_NETWORK = "NO_NETWORK";
    /** {@link FetchOutcome#NS_NOT_RESPONDING}: connect failure with a network. */
    public static final String CONNECT_FAILED = "CONNECT_FAILED";
    /** {@link FetchOutcome#NS_NOT_RESPONDING}: timeout. */
    public static final String TIMEOUT = "TIMEOUT";
    /** {@link FetchOutcome#NS_NOT_RESPONDING}: TLS failure. */
    public static final String TLS = "TLS";
    /** {@link FetchOutcome#NS_NOT_RESPONDING}: HTTP 5xx. */
    public static final String HTTP_5XX = "HTTP_5XX";
    /** {@link FetchOutcome#NS_NOT_RESPONDING}: the body is not JSON. */
    public static final String NON_JSON = "NON_JSON";
    /** {@link FetchOutcome#NS_NOT_RESPONDING}: cross-origin or https→http redirect refused (C-10, SR-17). */
    public static final String REDIRECT_REFUSED = "REDIRECT_REFUSED";
    /** {@link FetchOutcome#NS_NOT_RESPONDING}: response larger than 8 MB decompressed (C-11). */
    public static final String RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE";
    /** {@link FetchOutcome#TOKEN_REJECTED}: 401 after the single refresh, or IF-30 401. */
    public static final String AUTH_401 = "AUTH_401";
    /** {@link FetchOutcome#TOKEN_REJECTED}: 403. */
    public static final String FORBIDDEN_403 = "FORBIDDEN_403";
    /** {@link FetchOutcome#TOKEN_REJECTED}: no token / key invalidated (ADR-033). */
    public static final String TOKEN_MISSING = "TOKEN_MISSING";
    /** {@link FetchOutcome#NOT_NIGHTSCOUT}: non-JSON or no {@code result.version}. */
    public static final String NO_VERSION = "NO_VERSION";
    /** {@link FetchOutcome#NOT_NIGHTSCOUT}: {@code apiVersion} missing or not 3.x. */
    public static final String NOT_API_V3 = "NOT_API_V3";
    /** {@link FetchOutcome#SERVER_TOO_OLD}: version below 15.0.9. */
    public static final String VERSION_TOO_OLD = "VERSION_TOO_OLD";
    /** {@link FetchOutcome#PERMISSION_MISSING}: read on entries / treatments missing. */
    public static final String MISSING_READ = "MISSING_READ";
    /**
     * Treatments create / update / delete missing. {@code ServerPolicy} and the S-06 check report it with
     * {@link FetchOutcome#PERMISSION_MISSING}; the daily check stores it as the sticky
     * {@link FetchOutcome#PERMISSION_WRITE_MISSING}.
     */
    public static final String MISSING_WRITE = "MISSING_WRITE";
    /** {@link FetchOutcome#OK} with {@code ok() = false}: payload rejected (400 / 422). */
    public static final String REJECTED_400_422 = "REJECTED_400_422";
    /** {@link FetchOutcome#OK} with {@code ok() = false}: concurrent edit (412). */
    public static final String PRECONDITION_412 = "PRECONDITION_412";
    /** {@link FetchOutcome#OK} with {@code ok() = false}: document missing or deleted (404 / 410). */
    public static final String GONE_404_410 = "GONE_404_410";
    /** {@link FetchOutcome#OK} with {@code ok() = false}: the entry is not editable. */
    public static final String NOT_EDITABLE = "NOT_EDITABLE";
    /** {@link FetchOutcome#STORAGE_ERROR}: store failure. */
    public static final String STORAGE = "STORAGE";

    private DetailCode() {
    }
}
