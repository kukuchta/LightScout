package app.lightscout.domain.port;

import java.util.Optional;

/**
 * Source of the access token (IF-61). The caller wipes the returned array after use (SR-02).
 */
public interface TokenSource {

    /**
     * Returns the decrypted token.
     *
     * @return the token, or empty if none / key invalidated
     */
    Optional<char[]> token();
}
