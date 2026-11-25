package app.lightscout.usecases.common;

import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.settings.Settings;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The one writer of ST-02 settings (SS-06 IR-44; CP-02 §8.7 / §8.8, R-29, R-30, ASR-17). IF-26 (SettingsService, on
 * the calling thread) and IF-25 (AlarmActions switches, on {@code monitorExecutor}) both write the whole
 * {@link Settings} record; doing every get→put under this one lock means neither can silently revert the other.
 * The lock is held only for the get→put — never while waiting for an executor. The composition root creates one
 * instance and passes it to both use cases.
 */
public final class SettingsWriter {

    private final SettingsStore store;
    private final Object lock = new Object();

    /**
     * Result of {@link #apply}.
     *
     * @param before  settings read under the lock
     * @param after   settings after the change ({@code before} when nothing was written)
     * @param written {@code true} when a new value was stored
     */
    public record Applied(Settings before, Settings after, boolean written) {
    }

    /**
     * Creates the writer.
     *
     * @param store IF-11
     */
    public SettingsWriter(SettingsStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Reads the settings (no lock needed: the store commits whole records).
     *
     * @return the stored settings
     * @throws app.lightscout.domain.port.StorageException on a store failure
     */
    public Settings read() {
        return store.get();
    }

    /**
     * Reads, changes and stores the settings atomically with respect to every other caller of this writer.
     *
     * @param change maps the current settings to the new ones; {@code null} or an equal value = write nothing
     * @return what happened
     * @throws app.lightscout.domain.port.StorageException on a store failure (nothing is stored then)
     */
    public Applied apply(UnaryOperator<Settings> change) {
        synchronized (lock) {
            Settings current = store.get();
            Settings next = change.apply(current);
            if (next == null || next.equals(current)) {
                return new Applied(current, current, false);
            }
            store.put(next);
            return new Applied(current, next, true);
        }
    }
}
