package app.lightscout.domain.port;

import java.time.Instant;

/**
 * Persisted state of the one-time support prompt (DCR-46, D-166): when the app was first used and whether the
 * prompt was shown. Implemented by {@code :storage} in the ST-04 {@code runtime} preferences (credential-encrypted,
 * after unlock only). Blocking: call off the main thread.
 */
public interface SupportPromptStore {

    /**
     * Returns when the app was first used. The first call stores {@code now} and returns it; later calls return the
     * stored value.
     *
     * @param now current instant, stored when none is stored yet
     * @return the first-use instant
     */
    Instant firstUse(Instant now);

    /**
     * Returns whether the prompt was shown before.
     *
     * @return flag
     */
    boolean shown();

    /** Marks the prompt shown. It is never shown again, whatever the user did with it. */
    void markShown();
}
