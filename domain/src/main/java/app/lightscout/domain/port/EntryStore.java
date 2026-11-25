package app.lightscout.domain.port;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Entry cache (IF-09, CP-00A R-23). Failures throw {@link StorageException}.
 */
public interface EntryStore {

    /**
     * Inserts or updates entries.
     *
     * @param e entries
     */
    void upsert(List<Entry> e);

    /**
     * Deletes rows with this origin, time in {@code [from, to)} and id not in the set; upserts the set.
     * Never called with an incomplete page (C-8).
     *
     * @param origin            origin scope
     * @param from              inclusive start
     * @param to                exclusive end
     * @param completeServerSet the complete server set
     */
    void replaceWindow(EntryOrigin origin, Instant from, Instant to, List<Entry> completeServerSet);

    /**
     * Returns entries with time in {@code [from, to)}.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @return entries
     */
    List<Entry> range(Instant from, Instant to);

    /**
     * Returns one entry.
     *
     * @param id identifier
     * @return the entry, if cached
     */
    Optional<Entry> get(String id);

    /**
     * Returns the latest entry of a type.
     *
     * @param t type
     * @return the entry, if any
     */
    Optional<Entry> latestOfType(EntryType t);

    /**
     * Deletes one entry.
     *
     * @param id identifier
     */
    void delete(String id);

    /**
     * Deletes entries before a time.
     *
     * @param t bound
     */
    void evictBefore(Instant t);

    /** Deletes all entries. */
    void clear();
}
