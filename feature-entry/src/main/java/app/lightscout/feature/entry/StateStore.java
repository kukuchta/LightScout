package app.lightscout.feature.entry;

/**
 * Minimal saved-state port used by the S-02 form model (CP-00 §6, CP-13 §7.7). Production code wraps the ViewModel's
 * {@code SavedStateHandle} ({@link SavedStateHandleStore}); JVM tests use an in-memory map. Holds type, value text,
 * time, identifier and the "save in flight" flag: app-private transient health data (ENT-12), never logged (SR-07).
 */
public interface StateStore {

    /**
     * Tells whether a value is stored under a key.
     *
     * @param key the key
     * @return {@code true} if present
     */
    boolean contains(String key);

    /**
     * Reads a string.
     *
     * @param key the key
     * @return the value, or {@code null} when absent
     */
    String getString(String key);

    /**
     * Writes a string.
     *
     * @param key   the key
     * @param value the value (never a secret)
     */
    void putString(String key, String value);

    /**
     * Reads a boolean.
     *
     * @param key      the key
     * @param fallback value returned when absent
     * @return the stored value or {@code fallback}
     */
    boolean getBoolean(String key, boolean fallback);

    /**
     * Writes a boolean.
     *
     * @param key   the key
     * @param value the value
     */
    void putBoolean(String key, boolean value);
}
