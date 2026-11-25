package app.lightscout.nightscout;

import java.util.Locale;

import okhttp3.HttpUrl;

/**
 * Web origin = scheme + host + port (C-10, C-13, SR-17, SR-19). The token and {@code Authorization} only ever go
 * to the configured origin.
 *
 * @param scheme lower-case scheme
 * @param host   lower-case host
 * @param port   effective port
 */
record Origin(String scheme, String host, int port) {

    /**
     * Returns the origin of a URL.
     *
     * @param url the URL
     * @return its origin
     */
    static Origin of(HttpUrl url) {
        return new Origin(url.scheme().toLowerCase(Locale.ROOT), url.host().toLowerCase(Locale.ROOT), url.port());
    }

    /**
     * Redirect rule (C-10, SR-17, CP-00A R-40): a redirect is followed only to the same origin over https.
     * https→http and any cross-origin target are refused. {@code allowCleartext} exists only for the JVM test
     * client on plain-http mockwebserver; the same-origin requirement applies there too.
     *
     * @param configured     origin of the request that was redirected
     * @param target         resolved {@code Location}, {@code null} if missing or unparseable
     * @param allowCleartext whether plain http is permitted (test client only)
     * @return {@code true} if the redirect may be followed
     */
    static boolean redirectAllowed(Origin configured, HttpUrl target, boolean allowCleartext) {
        if (configured == null || target == null) {
            return false;
        }
        if (!target.isHttps() && !allowCleartext) {
            return false;
        }
        return configured.equals(of(target));
    }

    @Override
    public String toString() {
        // The host is never printed (C-9, SR-07).
        return "Origin[masked]";
    }
}
