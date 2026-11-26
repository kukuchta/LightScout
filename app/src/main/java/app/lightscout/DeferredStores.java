package app.lightscout;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.sound.PlayableSound;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Store ports whose {@code :storage} implementation is created on the <b>first store call</b>, not when the
 * container wires them (CP-15 §8.1 „each component is built on first use”).
 *
 * <p>Why: {@code StorageFactory} opens {@code SharedPreferences} when it creates a store, which is a disk access
 * (directory check). The {@code :monitor} / {@code :alarm} components and the features obtain the wired graph on the
 * main thread ({@code MonitorService.onStartCommand}, {@code AlarmActivity}, ViewModel factories), where no disk
 * access is allowed (CP-00 §4, StrictMode penaltyDeath; SS-09 §5.4, SS-10 §5.2). Store methods themselves are always
 * called off the main thread (ADR-024, R-34), so the real store is created there. The DE {@link AppFlagsStore}
 * follows the same rule, so nothing is opened in {@code Application.onCreate} (direct boot, CP-00 §6).
 *
 * <p>Every adapter delegates each call unchanged; failures ({@code StorageException}) pass through.
 */
final class DeferredStores {

    private DeferredStores() {
    }

    /**
     * Thread-safe memo of one store; the supplier runs at most once (on the thread of the first call).
     *
     * @param <T> store type
     */
    static class Memo<T> {
        private final Supplier<T> factory;
        private volatile T value;

        Memo(Supplier<T> factory) {
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        T get() {
            T v = value;
            if (v == null) {
                synchronized (this) {
                    v = value;
                    if (v == null) {
                        v = Objects.requireNonNull(factory.get(), "store");
                        value = v;
                        onCreated(v);
                    }
                }
            }
            return v;
        }

        /**
         * Hook called once, inside the lock, right after creation.
         *
         * @param created the new store
         */
        void onCreated(T created) {
            // default: nothing
        }
    }

    /** IF-60. */
    static final class AppFlags implements AppFlagsStore {
        private final Memo<AppFlagsStore> store;

        AppFlags(Supplier<AppFlagsStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public boolean disclaimerAccepted() {
            return store.get().disclaimerAccepted();
        }

        @Override
        public void setDisclaimerAccepted() {
            store.get().setDisclaimerAccepted();
        }

        @Override
        public boolean onboardingCompleted() {
            return store.get().onboardingCompleted();
        }

        @Override
        public void setOnboardingCompleted(boolean v) {
            store.get().setOnboardingCompleted(v);
        }

        @Override
        public boolean monitoringConfigured() {
            return store.get().monitoringConfigured();
        }

        @Override
        public void setMonitoringConfigured(boolean v) {
            store.get().setMonitoringConfigured(v);
        }

        @Override
        public long handledBootCount() {
            return store.get().handledBootCount();
        }

        @Override
        public void setHandledBootCount(long c) {
            store.get().setHandledBootCount(c);
        }
    }

    /** IF-08. */
    static final class Readings implements ReadingStore {
        private final Memo<ReadingStore> store;

        Readings(Supplier<ReadingStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public void upsert(List<Reading> r) {
            store.get().upsert(r);
        }

        @Override
        public List<Reading> range(Instant from, Instant to) {
            return store.get().range(from, to);
        }

        @Override
        public Optional<Reading> latestNotAfter(Instant t) {
            return store.get().latestNotAfter(t);
        }

        @Override
        public void replaceWindow(Instant from, Instant to, List<Reading> completeServerSet) {
            store.get().replaceWindow(from, to, completeServerSet);
        }

        @Override
        public void evictBefore(Instant t) {
            store.get().evictBefore(t);
        }

        @Override
        public void clear() {
            store.get().clear();
        }
    }

    /** IF-09. */
    static final class Entries implements EntryStore {
        private final Memo<EntryStore> store;

        Entries(Supplier<EntryStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public void upsert(List<Entry> e) {
            store.get().upsert(e);
        }

        @Override
        public void replaceWindow(EntryOrigin origin, Instant from, Instant to, List<Entry> completeServerSet) {
            store.get().replaceWindow(origin, from, to, completeServerSet);
        }

        @Override
        public List<Entry> range(Instant from, Instant to) {
            return store.get().range(from, to);
        }

        @Override
        public Optional<Entry> get(String id) {
            return store.get().get(id);
        }

        @Override
        public Optional<Entry> latestOfType(EntryType t) {
            return store.get().latestOfType(t);
        }

        @Override
        public void delete(String id) {
            store.get().delete(id);
        }

        @Override
        public void evictBefore(Instant t) {
            store.get().evictBefore(t);
        }

        @Override
        public void clear() {
            store.get().clear();
        }
    }

    /** IF-10. */
    static final class DeviceStatuses implements DeviceStatusStore {
        private final Memo<DeviceStatusStore> store;

        DeviceStatuses(Supplier<DeviceStatusStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public void upsert(List<DeviceStatus> d) {
            store.get().upsert(d);
        }

        @Override
        public Optional<DeviceStatus> latest() {
            return store.get().latest();
        }

        @Override
        public void saveProfile(Profile p) {
            store.get().saveProfile(p);
        }

        @Override
        public Optional<Profile> latestProfile() {
            return store.get().latestProfile();
        }

        @Override
        public void evictBefore(Instant t) {
            store.get().evictBefore(t);
        }

        @Override
        public void clear() {
            store.get().clear();
        }
    }

    /**
     * IF-11. Listeners added before the store exists are kept here and served by one forwarding listener that is
     * registered with the real store when it is created (every {@code put} goes through this adapter, so the
     * forwarder is in place before the first change).
     */
    static final class SettingsPort implements SettingsStore {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final Memo<SettingsStore> store;

        SettingsPort(Supplier<SettingsStore> factory) {
            this.store = new Memo<>(factory) {
                @Override
                void onCreated(SettingsStore created) {
                    created.addListener(SettingsPort.this::fire);
                }
            };
        }

        @Override
        public Settings get() {
            return store.get().get();
        }

        @Override
        public void put(Settings s) {
            store.get().put(s);
        }

        @Override
        public void addListener(Runnable onChange) {
            listeners.add(Objects.requireNonNull(onChange, "onChange"));
        }

        private void fire() {
            for (Runnable l : listeners) {
                l.run();
            }
        }
    }

    /** IF-12 (also IF-61). */
    static final class Connection implements ConnectionStore {
        private final Memo<ConnectionStore> store;

        Connection(Supplier<ConnectionStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public Optional<String> baseUrl() {
            return store.get().baseUrl();
        }

        @Override
        public void save(String baseUrl, char[] token) {
            store.get().save(baseUrl, token);
        }

        @Override
        public void clear() {
            store.get().clear();
        }

        @Override
        public Optional<char[]> token() {
            return store.get().token();
        }
    }

    /** IF-13. */
    static final class MonitorStates implements MonitorStateStore {
        private final Memo<MonitorStateStore> store;

        MonitorStates(Supplier<MonitorStateStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public MonitorState get() {
            return store.get().get();
        }

        @Override
        public void put(MonitorState s) {
            store.get().put(s);
        }

        @Override
        public void clear() {
            store.get().clear();
        }
    }

    /** IF-14. */
    static final class AlarmStates implements AlarmStateStore {
        private final Memo<AlarmStateStore> store;

        AlarmStates(Supplier<AlarmStateStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public AlarmState get() {
            return store.get().get();
        }

        @Override
        public void put(AlarmState s) {
            store.get().put(s);
        }

        @Override
        public void clear() {
            store.get().clear();
        }
    }

    /** IF-63. */
    static final class ReminderStates implements ReminderStateStore {
        private final Memo<ReminderStateStore> store;

        ReminderStates(Supplier<ReminderStateStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public Optional<ReminderState> get(ConsumableKind k) {
            return store.get().get(k);
        }

        @Override
        public void put(ReminderState s) {
            store.get().put(s);
        }

        @Override
        public void clear() {
            store.get().clear();
        }
    }

    /** IF-64. */
    static final class NotifiedAlerts implements NotifiedAlertStore {
        private final Memo<NotifiedAlertStore> store;

        NotifiedAlerts(Supplier<NotifiedAlertStore> factory) {
            this.store = new Memo<>(factory);
        }

        @Override
        public boolean contains(String entryId) {
            return store.get().contains(entryId);
        }

        @Override
        public void add(String entryId, Instant entryTime) {
            store.get().add(entryId, entryTime);
        }

        @Override
        public void evictBefore(Instant t) {
            store.get().evictBefore(t);
        }

        @Override
        public void clear() {
            store.get().clear();
        }
    }

    /**
     * IF-69 (DCR-51). The real resolver, which opens the sound preferences, is created on the first
     * {@link #playable} call. That call comes from the alarm player thread, never from main, so wiring
     * {@code AlarmRuntime} on the main thread ({@code AlarmActivity}) touches no disk.
     */
    static final class SoundResolver implements AlarmSoundResolver {
        private final Memo<AlarmSoundResolver> resolver;

        SoundResolver(Supplier<AlarmSoundResolver> factory) {
            this.resolver = new Memo<>(factory);
        }

        @Override
        public PlayableSound playable(AlarmType type) {
            return resolver.get().playable(type);
        }

        @Override
        public void reportUnplayable(AlarmType type) {
            resolver.get().reportUnplayable(type);
        }
    }
}
