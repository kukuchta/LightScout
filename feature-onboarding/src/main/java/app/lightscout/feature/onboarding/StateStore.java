package app.lightscout.feature.onboarding;

/**
 * Minimal saved-state port used by the onboarding screen models (CP-00 §6). Production code wraps the
 * ViewModel's {@code SavedStateHandle} ({@link SavedStateHandleStore}); JVM tests use an in-memory map. Only
 * non-secret values are ever written: the S-06 URL and state flags, never the token (SR-02, SR-28).
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
