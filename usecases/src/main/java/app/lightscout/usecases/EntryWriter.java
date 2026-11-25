package app.lightscout.usecases;

import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.port.Callback;

import java.util.List;

/**
 * CMP-13 EntryWriter — IF-23 (CP-02 §8.5, CP-00A R-27). Online only, no outbox, no automatic retry (C-4, CON-06).
 * Methods with a callback work and call back on {@code ioExecutor}.
 */
public interface EntryWriter {

    /**
     * Creates a CREATE draft with a new UUID v4 identifier, generated once for the S-02 session and reused by every
     * retry (GIR-3). Memory only; allowed on the main thread.
     *
     * @return the draft
     */
    EntryDraft newDraft();

    /**
     * Loads an entry for editing: cache first, else IF-39; fills {@code expectedSrvModifiedMs}. Failures:
     * {@code GONE_404_410}, {@code NOT_EDITABLE} (R-50) and the network outcomes.
     *
     * @param identifier entry identifier
     * @param cb         receives the EDIT draft or a failure
     */
    void loadForEdit(String identifier, Callback<EntryDraft> cb);

    /**
     * Delivers cached entries of the draft's type within ±10 min of its time (or now) — {@code EntryRules.findSimilar}.
     *
     * @param d  the draft
     * @param cb receives the similar entries or a failure
     */
    void similarEntries(EntryDraft d, Callback<List<Entry>> cb);

    /**
     * Validates and writes a draft (IF-38 create / IF-40 update), then updates the cache. A second call for the same
     * identifier: an identical one joins the in-flight call (same result, once); a different one runs after it (GIR-1).
     *
     * @param d  the draft
     * @param cb receives the write result or a failure
     */
    void save(EntryDraft d, Callback<WriteResult> cb);

    /**
     * Deletes an own entry (IF-41) and removes it from the cache, also on 404 / 410 (reported as conflict).
     * A second call for the same identifier: an identical one joins the in-flight call (same result, once); a
     * different one runs after it (GIR-1).
     *
     * @param identifier entry identifier
     * @param cb         receives the write result or a failure
     */
    void delete(String identifier, Callback<WriteResult> cb);
}
