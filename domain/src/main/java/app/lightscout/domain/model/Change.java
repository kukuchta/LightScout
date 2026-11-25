package app.lightscout.domain.model;

import java.util.Objects;

/**
 * One document of a Nightscout history response (IF-37, CP-00A R-2).
 *
 * <p>Exactly one payload is non-null when {@code !deleted}; all payloads are null when deleted
 * ({@code isValid: false}).
 *
 * @param collection    source collection
 * @param id            Nightscout {@code identifier}
 * @param srvModifiedMs server modification time in epoch ms
 * @param deleted       {@code true} for a deletion
 * @param reading       payload for readings; nullable
 * @param entry         payload for entries / meter BG; nullable
 * @param deviceStatus  payload for device status; nullable
 */
public record Change(NsCollection collection, String id, long srvModifiedMs, boolean deleted,
                     Reading reading, Entry entry, DeviceStatus deviceStatus) {

    /**
     * Creates a change.
     *
     * @throws NullPointerException if {@code collection} or {@code id} is null
     */
    public Change {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(id, "id");
    }
}
