package app.lightscout.storage;

import android.content.SharedPreferences;

/**
 * Counter of {@code cache.db} re-creations, kept in the ST-04 {@code runtime} preferences (Q-31 / A-5 ruling,
 * SS-03).
 *
 * <p>The sync cursors that describe what the cache holds live in {@code MonitorState} (ST-04, CP-00A R-21). When
 * {@link CacheDatabase} drops and re-creates the cache (ADR-033), those cursors become stale and the cache must
 * be refetched in full. {@link CacheDatabase} therefore bumps this counter in the same open, and
 * {@link PrefsMonitorStateStore} serves empty cursors whenever the stored state was written under an older
 * counter value. The reset happens on read, so {@code monitorExecutor} stays the only writer of
 * {@code MonitorState} (R-21). The counter is cache metadata, not ENT-07…ENT-10, so it survives {@code clear()}.
 */
final class CacheGeneration {

    /** ST-04 key of the counter. */
    static final String KEY = "cache_generation";

    /** Value returned when the stored counter has an unexpected type; it never matches a written value. */
    static final long UNREADABLE = -1L;

    private final SharedPreferences prefs;

    /**
     * @param prefs the CE {@code runtime} preferences
     */
    CacheGeneration(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * @return the current counter, {@code 0} before the first re-creation, {@link #UNREADABLE} for a value of
     *         the wrong type
     */
    long current() {
        try {
            return prefs.getLong(KEY, 0L);
        } catch (ClassCastException e) {
            return UNREADABLE;
        }
    }

    /**
     * Increments the counter only if a {@code MonitorState} is stored, i.e. if stale cursors can exist (IR-20).
     *
     * @throws IllegalStateException if the commit fails
     */
    void bumpIfMonitorStateExists() {
        if (prefs.contains(PrefsMonitorStateStore.KEY)) {
            bump();
        }
    }

    /**
     * Increments the counter with {@code commit()}. Called by {@link CacheDatabase} inside the open transaction:
     * an exception there makes the open fail and the re-creation is rolled back and retried on the next open.
     *
     * @throws IllegalStateException if the commit fails
     */
    void bump() {
        long next = Math.max(current(), 0L) + 1L;
        SharedPreferences.Editor editor = prefs.edit().putLong(KEY, next);
        PrefsSchema.stamp(prefs, editor);
        if (!StoreGuard.commit(editor)) {
            throw new IllegalStateException("cache_generation commit failed");
        }
    }
}
