package app.lightscout.nightscout;

import app.lightscout.domain.port.FetchOutcome;

/**
 * Outcome of one HTTP exchange (after same-origin redirects): either a status code with the capped body
 * (2xx only) or a classified transport failure.
 *
 * @param code          HTTP status code; {@code -1} on failure
 * @param body          decoded body for 2xx responses, otherwise {@code null}
 * @param failure       classified outcome on failure, otherwise {@code null}
 * @param failureDetail detail code on failure, otherwise {@code null}
 */
record HttpReply(int code, String body, FetchOutcome failure, String failureDetail) {

    /**
     * Creates a reply for a received status.
     *
     * @param code status code
     * @param body body (2xx) or {@code null}
     * @return the reply
     */
    static HttpReply status(int code, String body) {
        return new HttpReply(code, body, null, null);
    }

    /**
     * Creates a failed reply.
     *
     * @param outcome classified outcome
     * @param detail  detail code
     * @return the reply
     */
    static HttpReply failed(FetchOutcome outcome, String detail) {
        return new HttpReply(-1, null, outcome, detail);
    }

    /**
     * Tells whether the exchange failed before a usable status was received.
     *
     * @return {@code true} on transport failure, refused redirect or oversized body
     */
    boolean isFailure() {
        return failure != null;
    }

    /**
     * Tells whether the status is 2xx.
     *
     * @return {@code true} for 200…299
     */
    boolean isSuccess() {
        return failure == null && code >= 200 && code < 300;
    }

    @Override
    public String toString() {
        // No body in the text (SR-07).
        return "HttpReply[code=" + code + ", failure=" + failure + ", detail=" + failureDetail + "]";
    }
}
