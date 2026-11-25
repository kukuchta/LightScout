package app.lightscout.domain.port;

import java.time.Instant;

import app.lightscout.domain.update.AppVersion;

/**
 * Persisted state of the update check (DCR-49): the user's on / off choice (default on), when GitHub was last asked,
 * the newest stable version it named and the newest version the dialog was shown for. Implemented by
 * {@code :storage} in the ST-04 {@code runtime} preferences (credential-encrypted, after unlock only). Blocking: call
 * off the main thread.
 */
public interface UpdateStore {

    /**
     * Returns whether the update check is on.
     *
     * @return {@code true} unless the user turned it off
     */
    boolean enabled();

    /**
     * Turns the update check on or off.
     *
     * @param enabled new choice
     */
    void setEnabled(boolean enabled);

    /**
     * Returns when GitHub was last asked.
     *
     * @return the instant, or {@code null} when never
     */
    Instant lastCheck();

    /**
     * Returns the newest stable version GitHub named.
     *
     * @return the version, or {@code null} when none is known
     */
    AppVersion latest();

    /**
     * Returns the newest version the dialog was shown for.
     *
     * @return the version, or {@code null} when never shown
     */
    AppVersion lastPrompted();

    /**
     * Records a check. The check time is always stored (a failed check is not repeated before the interval ends);
     * the latest version only when one was named.
     *
     * @param checkedAt when GitHub was asked
     * @param latest    the version it named; {@code null} keeps the stored one
     */
    void recordCheck(Instant checkedAt, AppVersion latest);

    /**
     * Records that the dialog was shown for a version.
     *
     * @param version the offered version
     */
    void markPrompted(AppVersion version);
}
