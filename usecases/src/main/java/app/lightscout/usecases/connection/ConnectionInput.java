package app.lightscout.usecases.connection;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;

/**
 * S-06 input rules, C-12 checks 1 and 2 (design S-06, SR-28; CP-02 §8.6) and the server identity of R-71. Pure
 * functions: CP-14 uses them for on-field validation (like {@code SettingsRules} for S-04) and
 * {@link DefaultConnectionService} applies them again before anything is sent.
 */
public final class ConnectionInput {

    private static final String HTTPS = "https";
    private static final String SCHEME_SEPARATOR = "://";
    private static final int DEFAULT_HTTPS_PORT = 443;

    private ConnectionInput() {
    }

    /** Input failure codes; each maps to one design §17 key rendered by CP-14. */
    public enum InputError {
        /** {@code s06_error_url_required}. */
        URL_REQUIRED,
        /** {@code s06_error_url_https}. */
        URL_HTTPS,
        /** {@code s06_error_url_invalid}. */
        URL_INVALID,
        /** {@code s06_error_token_required} (empty, or spaces inside after trimming). */
        TOKEN_REQUIRED
    }

    /**
     * Result of the URL check.
     *
     * @param normalisedUrl normalised base URL (server identity, R-71); {@code null} when invalid
     * @param host          lower-cased host; {@code null} when invalid
     * @param error         failure code; {@code null} when valid
     */
    public record UrlCheck(String normalisedUrl, String host, InputError error) {

        /**
         * Tells whether the URL is acceptable.
         *
         * @return {@code true} when valid
         */
        public boolean valid() {
            return error == null;
        }
    }

    /**
     * Checks and normalises a typed URL: required; „https://” added when no scheme is given; must be https; host
     * required; no user-info, no query, no fragment; a path is allowed (Nightscout behind a sub-path). Normalised
     * form (R-71): lower-case scheme and host, port 443 elided, path without trailing slash.
     *
     * @param typed the URL as typed; nullable
     * @return the check result
     */
    public static UrlCheck checkUrl(String typed) {
        String s = typed == null ? "" : typed.trim();
        if (s.isEmpty()) {
            return invalid(InputError.URL_REQUIRED);
        }
        int sep = s.indexOf(SCHEME_SEPARATOR);
        if (sep < 0) {
            s = HTTPS + SCHEME_SEPARATOR + s;
        } else if (!s.substring(0, sep).equalsIgnoreCase(HTTPS)) {
            return invalid(InputError.URL_HTTPS);
        }
        if (s.indexOf('?') >= 0 || s.indexOf('#') >= 0) {
            return invalid(InputError.URL_INVALID);
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            return invalid(InputError.URL_INVALID);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty() || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return invalid(InputError.URL_INVALID);
        }
        int port = uri.getPort();
        if (port == 0 || port > 65_535) {
            return invalid(InputError.URL_INVALID);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(HTTPS).append(SCHEME_SEPARATOR).append(lowerHost);
        if (port > 0 && port != DEFAULT_HTTPS_PORT) {
            out.append(':').append(port);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        out.append(path);
        return new UrlCheck(out.toString(), lowerHost, null);
    }

    /**
     * Checks a typed token: trimmed; required; no spaces inside.
     *
     * @param typed the token characters; nullable (not modified)
     * @return {@code null} when valid, else {@link InputError#TOKEN_REQUIRED}
     */
    public static InputError checkToken(char[] typed) {
        char[] t = normaliseToken(typed);
        if (t == null) {
            return InputError.TOKEN_REQUIRED;
        }
        Arrays.fill(t, '\0');
        return null;
    }

    /**
     * Returns a trimmed copy of a valid token. The caller wipes the copy (SR-02); the input is not modified.
     *
     * @param typed the token characters; nullable
     * @return the trimmed copy, or {@code null} when the token is empty or contains spaces
     */
    public static char[] normaliseToken(char[] typed) {
        if (typed == null) {
            return null;
        }
        int start = 0;
        int end = typed.length;
        while (start < end && Character.isWhitespace(typed[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(typed[end - 1])) {
            end--;
        }
        if (start == end) {
            return null;
        }
        for (int i = start; i < end; i++) {
            if (Character.isWhitespace(typed[i]) || Character.isSpaceChar(typed[i])) {
                return null;
            }
        }
        return Arrays.copyOfRange(typed, start, end);
    }

    /**
     * Returns the R-71 server identity of a stored base URL.
     *
     * @param storedUrl stored base URL; nullable
     * @return the normalised URL, or {@code null} when none / not parsable
     */
    public static String serverIdentity(String storedUrl) {
        return storedUrl == null ? null : checkUrl(storedUrl).normalisedUrl();
    }

    private static UrlCheck invalid(InputError e) {
        return new UrlCheck(null, null, e);
    }
}
