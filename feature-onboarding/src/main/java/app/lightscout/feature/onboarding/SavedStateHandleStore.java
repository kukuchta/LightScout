package app.lightscout.feature.onboarding;

import androidx.lifecycle.SavedStateHandle;

import java.util.Objects;

/**
 * {@link StateStore} backed by a ViewModel's {@link SavedStateHandle}: the values survive configuration changes
 * and process death (CP-00 §6). Never used for the token (SR-02).
 */
public final class SavedStateHandleStore implements StateStore {

    private final SavedStateHandle handle;

    /**
     * Wraps a handle.
     *
     * @param handle the ViewModel's saved-state handle
     */
    public SavedStateHandleStore(SavedStateHandle handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    @Override
    public boolean contains(String key) {
        return handle.contains(key);
    }

    @Override
    public String getString(String key) {
        Object v = handle.get(key);
        return v instanceof String ? (String) v : null;
    }

    @Override
    public void putString(String key, String value) {
        handle.set(key, value);
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        Object v = handle.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    @Override
    public void putBoolean(String key, boolean value) {
        handle.set(key, value);
    }
}
