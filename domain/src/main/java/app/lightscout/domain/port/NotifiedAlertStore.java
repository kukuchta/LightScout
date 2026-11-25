package app.lightscout.domain.port;

import java.time.Instant;

/**
 * Set of pump-alert entry ids already notified (IF-64, ENT-10, CP-00A R-22). Written only on
 * {@code monitorExecutor}. Failures throw {@link StorageException}.
 */
public interface NotifiedAlertStore {

    /**
     * Tells whether an alert was notified.
     *
     * @param entryId entry id
     * @return {@code true} if notified
     */
    boolean contains(String entryId);

    /**
     * Records a notified alert.
     *
     * @param entryId   entry id
     * @param entryTime entry time (for eviction)
     */
    void add(String entryId, Instant entryTime);

    /**
     * Deletes records with entry time before a bound.
     *
     * @param t bound
     */
    void evictBefore(Instant t);

    /** Deletes all records. */
    void clear();
}
