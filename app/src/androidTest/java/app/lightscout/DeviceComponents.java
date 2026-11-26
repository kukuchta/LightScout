package app.lightscout;

import android.content.Context;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.PumpAlertNotifier;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.domain.port.WriteAck;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.TickReason;
import app.lightscout.monitor.MonitorRuntime;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Device-test {@link ContainerComponents}: the real {@code :platform} services (channels, readiness, intents,
 * connectivity, clock, logger), but in-memory stores whose content each test sets, a recording IF-62 (no real
 * {@code MonitorService}) and a gateway that never contacts any server (CP-00 §8).
 */
final class DeviceComponents implements ContainerComponents {

    private final AndroidComponents platform;

    final MemoryFlags flags = new MemoryFlags();
    final MemoryConnection connection = new MemoryConnection();
    final MemoryState<MonitorState> monitorState = new MemoryState<>(MonitorState.initial());
    final MemoryState<AlarmState> alarmState = new MemoryState<>(AlarmState.empty());
    final RecordingControl control = new RecordingControl();
    /** S-09 live screen state of the (inert) alarm runtime; tests set it (IR-109). */
    volatile app.lightscout.domain.alarm.AlarmType liveScreen;
    /** IR-108: how often the F5 boot-count check task was created / run in this process. */
    final AtomicInteger bootCountChecksCreated = new AtomicInteger();
    final AtomicInteger bootCountChecksRun = new AtomicInteger();
    /**
     * BUG-14: gateways created later in this process are the production {@code OkHttpNightscoutGateway} (the
     * container memoises its gateways, so set this before the first one is created; never used for a network call).
     */
    volatile boolean realGateway;
    /** BUG-14: name of the thread on which each gateway was created. */
    final List<String> gatewayThreads = new CopyOnWriteArrayList<>();

    DeviceComponents(Context context) {
        this.platform = new AndroidComponents(context, true, "0.0.0-test");
    }

    /** Fresh store content for the next test. */
    void reset() {
        flags.disclaimerAccepted = false;
        flags.onboardingCompleted = false;
        flags.monitoringConfigured = false;
        flags.flagWrites.set(0);
        flags.resolveReads.set(0);
        connection.url = null;
        connection.token = null;
        monitorState.value = MonitorState.initial();
        alarmState.value = AlarmState.empty();
        control.calls.clear();
        liveScreen = null;
        realGateway = false;
    }

    /** State (b) of CP-15 §9: disclaimer accepted, connection saved, onboarding completed. */
    void configured() {
        flags.disclaimerAccepted = true;
        flags.onboardingCompleted = true;
        flags.monitoringConfigured = true;
        connection.url = "https://ns.example.org";
        connection.token = "test-token";
    }

    /** State (c) of CP-15 §9: as {@link #configured()} with the stored outcome TOKEN_REJECTED. */
    void configuredWithTokenRejected() {
        configured();
        MonitorState i = MonitorState.initial();
        monitorState.value = new MonitorState(i.monitoringSince(), i.lastSuccessfulCheck(), Instant.now(),
                FetchOutcome.TOKEN_REJECTED, i.connectionProblem(), i.lastReadingTime(), i.arrivalLagMedianSec(),
                i.recentLagSec(), i.lastDailyMaintenance(), i.cursors());
    }

    @Override
    public Clock clock() {
        return platform.clock();
    }

    @Override
    public Logger logger() {
        return platform.logger();
    }

    @Override
    public NotificationChannels channels() {
        return platform.channels();
    }

    @Override
    public AppFlagsStore appFlags() {
        return flags;
    }

    @Override
    public boolean isUserUnlocked() {
        return platform.isUserUnlocked();
    }

    @Override
    public String versionName() {
        return platform.versionName();
    }

    @Override
    public Connectivity connectivity() {
        return platform.connectivity();
    }

    @Override
    public ReadinessChecker readinessChecker(NotificationChannels channels) {
        return platform.readinessChecker(channels);
    }

    @Override
    public SystemSettingsIntents systemSettingsIntents(NotificationChannels channels) {
        return platform.systemSettingsIntents(channels);
    }

    @Override
    public ManufacturerDetector manufacturerDetector() {
        return platform.manufacturerDetector();
    }

    @Override
    public ReadingStore readings() {
        return new ReadingStore() {
            @Override
            public void upsert(List<Reading> r) {
            }

            @Override
            public List<Reading> range(Instant from, Instant to) {
                return List.of();
            }

            @Override
            public Optional<Reading> latestNotAfter(Instant t) {
                return Optional.empty();
            }

            @Override
            public void replaceWindow(Instant from, Instant to, List<Reading> completeServerSet) {
            }

            @Override
            public void evictBefore(Instant t) {
            }

            @Override
            public void clear() {
            }
        };
    }

    @Override
    public EntryStore entries() {
        return new EntryStore() {
            @Override
            public void upsert(List<Entry> e) {
            }

            @Override
            public void replaceWindow(EntryOrigin origin, Instant from, Instant to, List<Entry> completeServerSet) {
            }

            @Override
            public List<Entry> range(Instant from, Instant to) {
                return List.of();
            }

            @Override
            public Optional<Entry> get(String id) {
                return Optional.empty();
            }

            @Override
            public Optional<Entry> latestOfType(EntryType t) {
                return Optional.empty();
            }

            @Override
            public void delete(String id) {
            }

            @Override
            public void evictBefore(Instant t) {
            }

            @Override
            public void clear() {
            }
        };
    }

    @Override
    public DeviceStatusStore deviceStatus() {
        return new DeviceStatusStore() {
            @Override
            public void upsert(List<DeviceStatus> d) {
            }

            @Override
            public Optional<DeviceStatus> latest() {
                return Optional.empty();
            }

            @Override
            public void saveProfile(Profile p) {
            }

            @Override
            public Optional<Profile> latestProfile() {
                return Optional.empty();
            }

            @Override
            public void evictBefore(Instant t) {
            }

            @Override
            public void clear() {
            }
        };
    }

    @Override
    public SettingsStore settings() {
        return new SettingsStore() {
            private volatile Settings value = Settings.defaults();
            private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

            @Override
            public Settings get() {
                return value;
            }

            @Override
            public void put(Settings s) {
                value = s;
                listeners.forEach(Runnable::run);
            }

            @Override
            public void addListener(Runnable onChange) {
                listeners.add(onChange);
            }
        };
    }

    @Override
    public ConnectionStore connection() {
        return connection;
    }

    @Override
    public MonitorStateStore monitorState() {
        return new MonitorStateStore() {
            @Override
            public MonitorState get() {
                return monitorState.value;
            }

            @Override
            public void put(MonitorState s) {
                monitorState.value = s;
            }

            @Override
            public void clear() {
                monitorState.value = MonitorState.initial();
            }
        };
    }

    @Override
    public AlarmStateStore alarmState() {
        return new AlarmStateStore() {
            @Override
            public AlarmState get() {
                return alarmState.value;
            }

            @Override
            public void put(AlarmState s) {
                alarmState.value = s;
            }

            @Override
            public void clear() {
                alarmState.value = AlarmState.empty();
            }
        };
    }

    @Override
    public app.lightscout.domain.port.AlarmSoundStore alarmSoundStore() {
        throw new UnsupportedOperationException("the sound store is not used by these tests");
    }

    @Override
    public app.lightscout.domain.port.AudioSourcePort audioSource() {
        throw new UnsupportedOperationException("the audio source is not used by these tests");
    }

    @Override
    public app.lightscout.domain.port.UpdateStore updateStore() {
        return new app.lightscout.domain.port.UpdateStore() {
            private boolean enabled = true;
            private java.time.Instant lastCheck;
            private app.lightscout.domain.update.AppVersion latest;
            private app.lightscout.domain.update.AppVersion prompted;

            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public void setEnabled(boolean value) {
                enabled = value;
            }

            @Override
            public java.time.Instant lastCheck() {
                return lastCheck;
            }

            @Override
            public app.lightscout.domain.update.AppVersion latest() {
                return latest;
            }

            @Override
            public app.lightscout.domain.update.AppVersion lastPrompted() {
                return prompted;
            }

            @Override
            public void recordCheck(java.time.Instant checkedAt, app.lightscout.domain.update.AppVersion named) {
                lastCheck = checkedAt;
                if (named != null) {
                    latest = named;
                }
            }

            @Override
            public void markPrompted(app.lightscout.domain.update.AppVersion version) {
                prompted = version;
            }
        };
    }

    /** The UI tests never reach GitHub: no release is ever named. */
    @Override
    public app.lightscout.domain.port.UpdateSource updateSource() {
        return () -> null;
    }

    @Override
    public app.lightscout.domain.port.SupportPromptStore supportPrompt() {
        return new app.lightscout.domain.port.SupportPromptStore() {
            private java.time.Instant first;
            private boolean shown;

            @Override
            public java.time.Instant firstUse(java.time.Instant now) {
                if (first == null) {
                    first = now;
                }
                return first;
            }

            @Override
            public boolean shown() {
                return shown;
            }

            @Override
            public void markShown() {
                shown = true;
            }
        };
    }

    @Override
    public ReminderStateStore reminderState() {
        return new ReminderStateStore() {
            @Override
            public Optional<ReminderState> get(ConsumableKind k) {
                return Optional.empty();
            }

            @Override
            public void put(ReminderState s) {
            }

            @Override
            public void clear() {
            }
        };
    }

    @Override
    public NotifiedAlertStore notifiedAlerts() {
        return new NotifiedAlertStore() {
            @Override
            public boolean contains(String entryId) {
                return false;
            }

            @Override
            public void add(String entryId, Instant entryTime) {
            }

            @Override
            public void evictBefore(Instant t) {
            }

            @Override
            public void clear() {
            }
        };
    }

    @Override
    public NightscoutGateway gateway(Connectivity connectivity) {
        gatewayThreads.add(Thread.currentThread().getName());
        if (realGateway) {
            // BUG-14: the production OkHttpNightscoutGateway (OkHttpClient, SSLContext); creating it sends nothing
            return platform.gateway(connectivity);
        }
        return new OfflineGateway();
    }

    @Override
    public ReminderScheduler reminderScheduler() {
        return new ReminderScheduler() {
            @Override
            public void post(ReminderNotice n) {
            }

            @Override
            public void cancel(ConsumableKind k) {
            }
        };
    }

    @Override
    public PumpAlertNotifier pumpAlertNotifier() {
        return alert -> { };
    }

    @Override
    public MonitorRuntimeHandle monitorRuntime(MonitorRuntime.Collaborators collaborators) {
        return new MonitorRuntimeHandle(null, control);
    }

    @Override
    public AlarmRuntimeHandle alarmRuntime(Supplier<AlarmActions> alarmActions, AlarmStateStore alarmStateStore,
                                           AlarmStateMemory alarmMemory, NotificationChannels channels,
                                           Executor monitorExecutor) {
        return new AlarmRuntimeHandle(null, (List<AlarmCommand> commands) -> { }, () -> liveScreen);
    }

    @Override
    public Runnable bootCountCheck(AppFlagsStore flags) {
        bootCountChecksCreated.incrementAndGet();
        return () -> {
            bootCountChecksRun.incrementAndGet();
            control.calls.add("bootCountCheck");
        };
    }

    /** IF-60 in memory; counts writes and the reads IF-27 {@code resolve()} makes first. */
    static final class MemoryFlags implements AppFlagsStore {
        volatile boolean disclaimerAccepted;
        volatile boolean onboardingCompleted;
        volatile boolean monitoringConfigured;
        final AtomicInteger flagWrites = new AtomicInteger();
        final AtomicInteger resolveReads = new AtomicInteger();

        @Override
        public boolean disclaimerAccepted() {
            resolveReads.incrementAndGet();
            return disclaimerAccepted;
        }

        @Override
        public void setDisclaimerAccepted() {
            flagWrites.incrementAndGet();
            disclaimerAccepted = true;
        }

        @Override
        public boolean onboardingCompleted() {
            return onboardingCompleted;
        }

        @Override
        public void setOnboardingCompleted(boolean v) {
            flagWrites.incrementAndGet();
            onboardingCompleted = v;
        }

        @Override
        public boolean monitoringConfigured() {
            return monitoringConfigured;
        }

        @Override
        public void setMonitoringConfigured(boolean v) {
            flagWrites.incrementAndGet();
            monitoringConfigured = v;
        }

        @Override
        public long handledBootCount() {
            return 0;
        }

        @Override
        public void setHandledBootCount(long c) {
        }
    }

    /** IF-12 in memory (test token only, never a real one — SR-40). */
    static final class MemoryConnection implements ConnectionStore {
        volatile String url;
        volatile String token;

        @Override
        public Optional<String> baseUrl() {
            return Optional.ofNullable(url);
        }

        @Override
        public void save(String baseUrl, char[] token) {
            this.url = baseUrl;
            this.token = new String(token);
        }

        @Override
        public void clear() {
            url = null;
            token = null;
        }

        @Override
        public Optional<char[]> token() {
            String t = token;
            return t == null ? Optional.empty() : Optional.of(t.toCharArray());
        }
    }

    /** One mutable value. */
    static final class MemoryState<T> {
        volatile T value;

        MemoryState(T initial) {
            this.value = initial;
        }
    }

    /** IF-62 that records calls instead of starting {@code MonitorService}. */
    static final class RecordingControl implements MonitorControl {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void requestTick(TickReason r) {
            calls.add("requestTick:" + r);
        }

        @Override
        public void reevaluateAlarms() {
            calls.add("reevaluateAlarms");
        }

        @Override
        public void startMonitoring() {
            calls.add("startMonitoring");
        }
    }

    /** IF-07 that never opens a socket: every call reports NO_INTERNET (CP-00 §8). */
    static final class OfflineGateway implements NightscoutGateway {
        private static <T> Result<T> offline() {
            return Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        }

        @Override
        public void configure(String baseUrl, TokenSource tokens) {
        }

        @Override
        public Result<ServerInfo> version(String baseUrl) {
            return offline();
        }

        @Override
        public Result<Permissions> permissions() {
            return offline();
        }

        @Override
        public Result<Map<NsCollection, Long>> lastModified() {
            return offline();
        }

        @Override
        public Result<Page<Reading>> readingsSince(long fromMsExclusive, int limit) {
            return offline();
        }

        @Override
        public Result<Page<Entry>> meterBgSince(long fromMsExclusive, int limit) {
            return offline();
        }

        @Override
        public Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit) {
            return offline();
        }

        @Override
        public Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit) {
            return offline();
        }

        @Override
        public Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit) {
            return offline();
        }

        @Override
        public Result<List<DeviceStatus>> deviceStatusSince(Instant from) {
            return offline();
        }

        @Override
        public Result<Profile> latestProfile() {
            return offline();
        }

        @Override
        public Result<Page<Change>> history(NsCollection c, long cursorMs, int limit) {
            return offline();
        }

        @Override
        public Result<WriteAck> create(Entry e) {
            return offline();
        }

        @Override
        public Result<Entry> get(String id) {
            return offline();
        }

        @Override
        public Result<WriteAck> update(Entry e, long expectedSrvModifiedMs) {
            return offline();
        }

        @Override
        public Result<WriteAck> delete(String id) {
            return offline();
        }
    }
}
