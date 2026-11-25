package app.lightscout.domain.port;

/**
 * Outcome of a remote call or a sync tick (CP-00 §5; the approved 8-value set, plus {@link #PERMISSION_WRITE_MISSING}
 * for the sticky daily-check problem only — SS-35 amendment CR-11, confirmed by the PO in D-140).
 */
public enum FetchOutcome {
    /** The call succeeded (or the server answered and refused — see {@link Result#ok()} and {@link DetailCode}). */
    OK,
    /** No internet connection on this phone. */
    NO_INTERNET,
    /** Transport or server failure while a network is available. */
    NS_NOT_RESPONDING,
    /** The server is not a Nightscout API v3 server. */
    NOT_NIGHTSCOUT,
    /** The token was rejected or is missing. */
    TOKEN_REJECTED,
    /** A required permission is missing. */
    PERMISSION_MISSING,
    /** Sticky daily-check problem only: the token can read, but cannot create, update and delete treatments. */
    PERMISSION_WRITE_MISSING,
    /** The Nightscout version is too old. */
    SERVER_TOO_OLD,
    /** A local store failed. */
    STORAGE_ERROR
}
