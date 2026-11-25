package app.lightscout.usecases.settings;

import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.SettingsRules;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.common.SettingsEditor;
import app.lightscout.usecases.common.SettingsWriter;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * CMP-16 implementation of IF-26 (CP-02 §8.8, DS:A-21, ASR-17; CP-00A R-30, R-34; Architect ruling on SS-06 A-3).
 *
 * <p>{@link #update} runs on the calling thread (callers are off the main thread — R-34): under a SettingsService
 * lock it reads the settings, validates the change against them, stores a valid change and returns only after the
 * store has committed it (read-your-write). The lock is the shared {@link SettingsWriter} lock, also taken by the
 * IF-25 switch writes, so neither path can revert the other (IR-44). Then {@code MonitorControl.reevaluateAlarms()} is requested (ASR-17). It never waits on
 * {@code monitorExecutor}.
 *
 * <p>Listeners are fed from IF-11 {@code addListener}: every stored change (also an IF-25 switch) re-reads the
 * settings and tells every listener on the writer thread. Settings are per phone, never synced.
 */
public final class DefaultSettingsService implements SettingsService {

    private final SettingsStore store;
    private final MonitorControl monitorControl;
    private final SettingsWriter writer;
    private final List<Consumer<Settings>> listeners = new CopyOnWriteArrayList<>();
    private volatile Settings lastKnown;

    /**
     * Creates the service and subscribes to IF-11 changes.
     *
     * @param store          IF-11 (reads, change listener)
     * @param writer         the shared ST-02 writer, also used by IF-25 (IR-44)
     * @param monitorControl IF-62 ({@code reevaluateAlarms} after a stored change)
     */
    public DefaultSettingsService(SettingsStore store, SettingsWriter writer, MonitorControl monitorControl) {
        this.store = Objects.requireNonNull(store, "store");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.monitorControl = Objects.requireNonNull(monitorControl, "monitorControl");
        store.addListener(this::onStoreChanged);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A store failure (already logged by the store, R-13) returns the last settings read in this process, else
     * the defaults; it never throws (R-20, SR-37).
     */
    @Override
    public Settings get() {
        try {
            Settings s = store.get();
            lastKnown = s;
            return s;
        } catch (StorageException e) {
            Settings known = lastKnown;
            return known != null ? known : Settings.defaults();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>When the settings cannot be read, the change is validated against the last known settings (else the
     * defaults) and not stored: nothing is written over an unreadable store. A failing {@code put} stores nothing and
     * requests no re-evaluation.
     */
    @Override
    public ValidationResult update(SettingChange c) {
        Objects.requireNonNull(c, "change");
        ValidationResult[] validated = new ValidationResult[1];
        SettingsWriter.Applied applied;
        try {
            applied = writer.apply(current -> {
                ValidationResult v = SettingsRules.validate(current, c);
                validated[0] = v;
                return v.valid() ? SettingsEditor.withChange(current, c) : null;
            });
        } catch (StorageException e) {
            // logged by the store (R-13). Read failed: validate against the last known settings, store nothing.
            // Put failed: nothing stored, nothing to apply.
            return validated[0] != null ? validated[0] : SettingsRules.validate(get(), c);
        }
        lastKnown = applied.after();
        // The statistics card and the theme are presentation settings: no alarm rule depends on them.
        if (applied.written() && c.key() != SettingKey.STATISTICS_CARD && c.key() != SettingKey.THEME_MODE) {
            monitorControl.reevaluateAlarms();
        }
        return validated[0];
    }

    @Override
    public void addListener(Consumer<Settings> l) {
        listeners.add(Objects.requireNonNull(l, "listener"));
    }

    @Override
    public void removeListener(Consumer<Settings> l) {
        listeners.remove(l);
    }

    /** IF-11 change callback (writer thread): re-read and tell every listener; one failing listener stops no other. */
    private void onStoreChanged() {
        if (listeners.isEmpty()) {
            return;
        }
        Settings s = get();
        for (Consumer<Settings> l : listeners) {
            try {
                l.accept(s);
            } catch (RuntimeException e) {
                // a listener failure must not break the writer (SR-37)
            }
        }
    }
}
