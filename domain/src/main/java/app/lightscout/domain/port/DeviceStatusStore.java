package app.lightscout.domain.port;

import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Profile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Device status and profile cache (IF-10). Failures throw {@link StorageException}.
 */
public interface DeviceStatusStore {

    /**
     * Inserts or updates device status documents.
     *
     * @param d documents
     */
    void upsert(List<DeviceStatus> d);

    /**
     * Returns the latest device status.
     *
     * @return the status, if any
     */
    Optional<DeviceStatus> latest();

    /**
     * Stores the profile.
     *
     * @param p profile
     */
    void saveProfile(Profile p);

    /**
     * Returns the stored profile.
     *
     * @return the profile, if any
     */
    Optional<Profile> latestProfile();

    /**
     * Deletes documents before a time.
     *
     * @param t bound
     */
    void evictBefore(Instant t);

    /** Deletes everything. */
    void clear();
}
