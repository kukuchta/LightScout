package app.lightscout.domain.port;

import app.lightscout.domain.settings.Settings;

/**
 * Settings persistence (IF-11). Failures throw {@link StorageException}.
 */
public interface SettingsStore {

    /**
     * Returns the stored settings (defaults when none).
     *
     * @return settings
     */
    Settings get();

    /**
     * Stores settings.
     *
     * @param s settings
     */
    void put(Settings s);

    /**
     * Registers a change listener.
     *
     * @param onChange called after a change
     */
    void addListener(Runnable onChange);
}
