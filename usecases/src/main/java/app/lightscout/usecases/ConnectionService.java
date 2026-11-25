package app.lightscout.usecases;

import app.lightscout.domain.port.Callback;

import java.util.List;

/**
 * CMP-14 ConnectionService — IF-24 (CP-02 §8.6, CP-00A R-28, R-71, R-73). Work and callbacks on {@code ioExecutor}.
 * Token arrays: the caller wipes every array it passed or received after the callback (SR-02).
 */
public interface ConnectionService {

    /**
     * Loads the stored connection without decrypting the token.
     *
     * @param cb receives the stored connection, or {@code ok(null)} when none is stored
     */
    void loadStored(Callback<StoredConnection> cb);

    /**
     * Runs the S-06 checks in the mandatory C-12 order (SR-12): URL and token input, IF-31 version without token,
     * version / API rule, then token and permissions. The token is never sent to a host that failed the identity
     * or version check. An identical call (same URL, token mode and token) joins the one in flight
     * (same result, once); a different one runs after it (GIR-1).
     *
     * @param url         URL as typed
     * @param tokenOrNull typed token, or {@code null} for the stored token (untouched masked field)
     * @param cb          receives the result rows up to and including the failing one (empty when the input is
     *                    invalid — see {@code ConnectionInput})
     */
    void verify(String url, char[] tokenOrNull, Callback<List<CheckResult>> cb);

    /**
     * Decrypts the stored token on demand (S-06 „Show token” on the untouched field — D-027).
     *
     * @param cb receives the token characters (caller wipes) or a {@code TOKEN_REJECTED} / {@code TOKEN_MISSING}
     *           failure
     */
    void revealStoredToken(Callback<char[]> cb);

    /**
     * Stores the connection after an all-✓ verify; a changed server identity resets cache and monitor / alarm state
     * on {@code monitorExecutor} (R-71, DS:A-23).
     *
     * @param url         URL as typed
     * @param tokenOrNull typed token, or {@code null} to keep the stored token
     * @param cb          receives whether the server changed, or a failure
     */
    void save(String url, char[] tokenOrNull, Callback<SaveResult> cb);
}
