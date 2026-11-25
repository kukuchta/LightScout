package app.lightscout.domain.port;

import java.util.Optional;

/**
 * Connection persistence: base URL and Keystore-encrypted token (IF-12, CP-00A R-39).
 * Failures throw {@link StorageException}.
 */
public interface ConnectionStore extends TokenSource {

    /**
     * Returns the stored base URL.
     *
     * @return the URL, if stored
     */
    Optional<String> baseUrl();

    /**
     * Stores the connection. The store neither wipes nor retains the array; the caller wipes it.
     *
     * @param baseUrl base URL
     * @param token   token characters
     */
    void save(String baseUrl, char[] token);

    /** Deletes the connection. */
    void clear();
}
