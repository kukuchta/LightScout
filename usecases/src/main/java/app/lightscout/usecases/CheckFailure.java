package app.lightscout.usecases;

/** Failure codes of the S-06 checks (CP-00A R-10, R-51); each maps to one design §17 key, rendered by CP-14. */
public enum CheckFailure {
    /** {@code s06_error_unreachable}. */
    UNREACHABLE,
    /** {@code s06_error_not_nightscout} (no version, not API v3, refused redirect — SR-17). */
    NOT_NIGHTSCOUT,
    /** {@code s06_error_version} with {@code {x.y.z}} = {@link CheckResult#serverVersion()}. */
    VERSION_TOO_OLD,
    /** {@code s06_error_token_rejected}. */
    TOKEN_REJECTED,
    /** {@code s06_error_no_read}. */
    NO_READ,
    /** {@code s06_error_no_write}. */
    NO_WRITE
}
