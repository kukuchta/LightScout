package app.lightscout.nightscout;

import java.io.IOException;
import java.io.InterruptedIOException;

import javax.net.ssl.SSLException;

import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;

/**
 * CMP-22 ErrorClassifier: maps transport failures and HTTP status codes to {@link FetchOutcome} and
 * {@link DetailCode} (C-5, CP-00A R-50). Never looks at exception messages (C-14).
 */
final class ErrorClassifier {

    private ErrorClassifier() {
    }

    /**
     * Classifies a transport exception (C-5). Without a validated network (IF-19) every transport failure is
     * {@code NO_INTERNET} — this covers DNS / connect failures and a network lost mid-request (R-51
     * „before or during the request”). With a network: TLS (also as cause / suppressed) → {@code TLS}, timeouts (also as
     * cause / suppressed) → {@code TIMEOUT}, anything
     * else (connection refused, DNS failure, reset, cleartext refused) → {@code CONNECT_FAILED}.
     *
     * @param e      the exception (its message is never read)
     * @param online {@link app.lightscout.domain.port.Connectivity#isOnline()} at the time of the failure
     * @return the failed reply
     */
    static HttpReply transport(IOException e, boolean online) {
        if (e instanceof ResponseTooLargeException) {
            return HttpReply.failed(FetchOutcome.NS_NOT_RESPONDING, DetailCode.RESPONSE_TOO_LARGE);
        }
        if (!online) {
            return HttpReply.failed(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        }
        if (contains(e, SSLException.class, 0)) {
            return HttpReply.failed(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TLS);
        }
        if (contains(e, InterruptedIOException.class, 0)) {
            // SocketTimeoutException (connect / read / TLS handshake read) and the OkHttp call timeout — also when
            // OkHttp 5 surfaces it as a suppressed route failure of a ConnectException (BUG-01).
            return HttpReply.failed(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        }
        return HttpReply.failed(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
    }

    /**
     * Tells whether the exception itself, a cause or a suppressed exception is of the given type. OkHttp 5 (fast
     * fallback) throws a {@code ConnectException} for a failed connection and attaches each route's real failure —
     * e.g. the TLS handshake failure or a handshake read timeout — as a suppressed exception (BUG-01).
     */
    private static boolean contains(Throwable t, Class<? extends Throwable> type, int depth) {
        if (t == null || depth > 4) {
            return false;
        }
        if (type.isInstance(t)) {
            return true;
        }
        for (Throwable s : t.getSuppressed()) {
            if (contains(s, type, depth + 1)) {
                return true;
            }
        }
        return contains(t.getCause(), type, depth + 1);
    }

    /**
     * Classifies a status that the calling contract does not handle itself: 401 → {@code TOKEN_REJECTED}
     * ({@code AUTH_401}), 403 → {@code TOKEN_REJECTED} ({@code FORBIDDEN_403}), 5xx → {@code NS_NOT_RESPONDING}
     * ({@code HTTP_5XX}), any other unexpected status (incl. 3xx not followed) → {@code NS_NOT_RESPONDING}
     * ({@code NON_JSON}: no usable Nightscout answer).
     *
     * @param code HTTP status
     * @param <T>  value type
     * @return the failed result
     */
    static <T> Result<T> status(int code) {
        if (code == 401) {
            return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        }
        if (code == 403) {
            return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        }
        if (code >= 500 && code < 600) {
            return Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        }
        return Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
    }

    /**
     * Converts a failed reply into a result.
     *
     * @param reply the failed reply
     * @param <T>   value type
     * @return the failed result
     */
    static <T> Result<T> failure(HttpReply reply) {
        return Result.fail(reply.failure(), reply.failureDetail());
    }

    /**
     * Result for a body that is not the expected JSON (C-5 „non-JSON … → NS_NOT_RESPONDING”).
     *
     * @param <T> value type
     * @return the failed result
     */
    static <T> Result<T> nonJson() {
        return Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
    }

    /**
     * Result for an unexpected internal failure caught at the port boundary (CP-00 §5: the adapter catches
     * everything). Reported as not responding so that nothing stops monitoring silently (SR-37).
     *
     * @param <T> value type
     * @return the failed result
     */
    static <T> Result<T> unexpected() {
        return Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
    }
}
