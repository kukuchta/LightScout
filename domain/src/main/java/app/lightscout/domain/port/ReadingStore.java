package app.lightscout.domain.port;

import app.lightscout.domain.model.Reading;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reading cache (IF-08, CP-00A R-23). Failures throw {@link StorageException}.
 */
public interface ReadingStore {

    /**
     * Inserts or updates readings.
     *
     * @param r readings
     */
    void upsert(List<Reading> r);

    /**
     * Returns readings with time in {@code [from, to)}.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @return readings
     */
    List<Reading> range(Instant from, Instant to);

    /**
     * Returns the latest reading not after a time.
     *
     * @param t upper bound
     * @return the reading, if any
     */
    Optional<Reading> latestNotAfter(Instant t);

    /**
     * Replaces the window {@code [from, to)} with a complete server set (SY-4 set difference).
     * Never called with an incomplete page (C-8).
     *
     * @param from              inclusive start
     * @param to                exclusive end
     * @param completeServerSet the complete server set
     */
    void replaceWindow(Instant from, Instant to, List<Reading> completeServerSet);

    /**
     * Deletes readings before a time.
     *
     * @param t bound
     */
    void evictBefore(Instant t);

    /** Deletes all readings. */
    void clear();
}
