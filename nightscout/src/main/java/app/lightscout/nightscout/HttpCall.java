package app.lightscout.nightscout;

import okhttp3.HttpUrl;

/**
 * Description of one Nightscout HTTP call.
 *
 * @param endpoint          call name for the debug timing line (never the URL — SR-07)
 * @param method            HTTP method
 * @param url               target URL (always on {@code origin})
 * @param jsonBody          JSON request body, {@code null} for none
 * @param ifUnmodifiedSince {@code If-Unmodified-Since} value (IF-40), {@code null} for none
 * @param write             {@code true} for IF-38…IF-41 (15 s budget, no retry)
 * @param jwt               JWT for {@code Authorization}, {@code null} for unauthenticated calls (IF-30, IF-31)
 * @param origin            the configured origin; redirects and {@code Authorization} are bound to it
 */
record HttpCall(Endpoint endpoint, String method, HttpUrl url, String jsonBody, String ifUnmodifiedSince,
                boolean write, String jwt, Origin origin) {

    /**
     * Creates an unauthenticated GET (IF-30, IF-31).
     *
     * @param endpoint call name
     * @param url      URL
     * @param origin   origin
     * @return the call
     */
    static HttpCall get(Endpoint endpoint, HttpUrl url, Origin origin) {
        return new HttpCall(endpoint, "GET", url, null, null, false, null, origin);
    }

    /**
     * Returns a copy carrying another JWT (repeat after the single refresh, C-2).
     *
     * @param newJwt the JWT
     * @return the call
     */
    HttpCall withJwt(String newJwt) {
        return new HttpCall(endpoint, method, url, jsonBody, ifUnmodifiedSince, write, newJwt, origin);
    }

    @Override
    public String toString() {
        // No URL, token or body in the text (C-9, SR-07).
        return "HttpCall[" + endpoint + ", " + method + ", write=" + write + "]";
    }
}
