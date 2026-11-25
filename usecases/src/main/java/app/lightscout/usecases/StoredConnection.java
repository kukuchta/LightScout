package app.lightscout.usecases;

import java.util.Objects;

/**
 * Stored connection without the token (IF-24 {@code loadStored}, CP-00A R-10, R-28): S-04 host row and S-06 edit
 * prefill. The token is never decrypted to build this.
 *
 * @param baseUrl  stored base URL
 * @param host     lower-cased host of the base URL (S-04 C-S04-13)
 * @param hasToken {@code true} when a token is stored with the URL
 */
public record StoredConnection(String baseUrl, String host, boolean hasToken) {

    /**
     * Creates the value.
     *
     * @throws NullPointerException if a component is null
     */
    public StoredConnection {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(host, "host");
    }
}
