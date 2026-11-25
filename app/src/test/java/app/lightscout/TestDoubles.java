package app.lightscout;

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
import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.EventCode;
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
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.ChannelState;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.OemSettingId;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.ReadinessItem;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Hand-written fakes for the {@code :app} JVM tests (no Mockito, CP-00 §2). */
final class TestDoubles {

    private TestDoubles() {
    }

    /**
     * Returns the fake behind a container gateway (BUG-14: the container hands out {@link DeferredGateway}s); creates
     * it on the calling thread if no gateway call happened yet.
     *
     * @param gateway a gateway from the container
     * @return the fake
     */
    static FakeGateway fake(NightscoutGateway gateway) {
        return (FakeGateway) ((DeferredGateway) gateway).delegate();
    }

    /** Records every store data call (method name) — wiring must make none. */
    static final class CallLog {
        final List<String> calls = new CopyOnWriteArrayList<>();

        void add(String call) {
            calls.add(call);
        }
    }

    /** Counting {@link ContainerComponents}: how often each component was created. */
    static final class FakeComponents implements ContainerComponents {
        final Map<String, AtomicInteger> created = new ConcurrentHashMap<>();
        final CallLog storeCalls = new CallLog();
        final FakeLogger logger = new FakeLogger();
        final FakeClock clock = new FakeClock();
        final List<FakeGateway> gateways = new CopyOnWriteArrayList<>();
        /** BUG-14: name of the thread on which each gateway was created. */
        final List<String> gatewayThreads = new CopyOnWriteArrayList<>();
        final FakeMonitorControl control = new FakeMonitorControl();
        final FakeAlarmController alarmController = new FakeAlarmController();
        volatile boolean unlocked;
        volatile app.lightscout.domain.alarm.AlarmType liveScreen;
        volatile String storedUrl = "https://ns.example.org";
        volatile AlarmStateMemory alarmMemoryGivenToRuntime;
        volatile Supplier<AlarmActions> alarmActionsSupplier;
        volatile MonitorRuntime.Collaborators monitorCollaborators;
        volatile Executor alarmRuntimeExecutor;
        final AtomicInteger bootChecks = new AtomicInteger();

        FakeComponents(boolean unlocked) {
            this.unlocked = unlocked;
            count("clock");
            count("logger");
        }

        int count(String name) {
            return created.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        }

        int created(String name) {
            AtomicInteger c = created.get(name);
            return c == null ? 0 : c.get();
        }

        @Override
        public Clock clock() {
            return clock;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public NotificationChannels channels() {
            count("channels");
            return new FakeChannels();
        }

        @Override
        public AppFlagsStore appFlags() {
            count("appFlags");
            return new FakeFlags(storeCalls);
        }

        @Override
        public boolean isUserUnlocked() {
            return unlocked;
        }

        @Override
        public String versionName() {
            return "1.2.3";
        }

        @Override
        public Connectivity connectivity() {
            count("connectivity");
            return new FakeConnectivity();
        }

        @Override
        public ReadinessChecker readinessChecker(NotificationChannels channels) {
            count("readinessChecker");
            return new FakeReadiness();
        }

        @Override
        public SystemSettingsIntents systemSettingsIntents(NotificationChannels channels) {
            count("systemSettingsIntents");
            return new FakeIntents();
        }

        @Override
        public ManufacturerDetector manufacturerDetector() {
            count("manufacturerDetector");
            return () -> Manufacturer.OTHER;
        }

        @Override
        public ReadingStore readings() {
            count("readings");
            return new FakeReadings(storeCalls);
        }

        @Override
        public EntryStore entries() {
            count("entries");
            return new FakeEntries(storeCalls);
        }

        @Override
        public DeviceStatusStore deviceStatus() {
            count("deviceStatus");
            return new FakeDeviceStatus(storeCalls);
        }

        @Override
        public SettingsStore settings() {
            count("settings");
            return new FakeSettings(storeCalls);
        }

        @Override
        public ConnectionStore connection() {
            count("connection");
            return new FakeConnection(storeCalls, this);
        }

        @Override
        public MonitorStateStore monitorState() {
            count("monitorState");
            return new FakeMonitorState(storeCalls);
        }

        @Override
        public AlarmStateStore alarmState() {
            count("alarmState");
            return new FakeAlarmState(storeCalls);
        }

        @Override
        public ReminderStateStore reminderState() {
            count("reminderState");
            return new FakeReminderState(storeCalls);
        }

        @Override
        public app.lightscout.domain.port.SupportPromptStore supportPrompt() {
            count("supportPrompt");
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
        public NotifiedAlertStore notifiedAlerts() {
            count("notifiedAlerts");
            return new FakeNotifiedAlerts(storeCalls);
        }

        @Override
        public NightscoutGateway gateway(Connectivity connectivity) {
            count("gateway");
            gatewayThreads.add(Thread.currentThread().getName());
            FakeGateway g = new FakeGateway();
            gateways.add(g);
            return g;
        }

        @Override
        public ReminderScheduler reminderScheduler() {
            count("reminderScheduler");
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
            count("pumpAlertNotifier");
            return alert -> { };
        }

        @Override
        public MonitorRuntimeHandle monitorRuntime(MonitorRuntime.Collaborators collaborators) {
            count("monitorRuntime");
            monitorCollaborators = collaborators;
            return new MonitorRuntimeHandle(null, control);
        }

        @Override
        public AlarmRuntimeHandle alarmRuntime(Supplier<AlarmActions> alarmActions, AlarmStateStore alarmStateStore,
                                               AlarmStateMemory alarmMemory, NotificationChannels channels,
                                               Executor monitorExecutor) {
            count("alarmRuntime");
            alarmActionsSupplier = alarmActions;
            alarmMemoryGivenToRuntime = alarmMemory;
            alarmRuntimeExecutor = monitorExecutor;
            return new AlarmRuntimeHandle(null, alarmController, () -> liveScreen);
        }

        @Override
        public Runnable bootCountCheck(AppFlagsStore flags) {
            count("bootCountCheck");
            return bootChecks::incrementAndGet;
        }
    }

    static class FakeLogger implements Logger {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> debugLines = new CopyOnWriteArrayList<>();

        @Override
        public void event(EventCode code) {
            events.add(code.name());
        }

        @Override
        public void event(EventCode code, String detail) {
            events.add(code.name() + ":" + detail);
        }

        @Override
        public void crash(String exceptionClass, String topAppFrame) {
            events.add("CRASH:" + exceptionClass + "@" + topAppFrame);
        }

        @Override
        public void debug(String tag, String message) {
            debugLines.add(tag + ": " + message);
        }
    }

    static final class FakeClock implements Clock {
        @Override
        public Instant now() {
            return Instant.parse("2026-10-16T10:00:00Z");
        }

        @Override
        public long elapsedRealtimeMs() {
            return 1_000L;
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("Europe/Warsaw");
        }
    }

    static final class FakeMonitorControl implements MonitorControl {
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

    static final class FakeAlarmController implements AlarmController {
        @Override
        public void apply(List<app.lightscout.domain.alarm.AlarmCommand> commands) {
        }
    }

    static final class FakeConnectivity implements Connectivity {
        final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public void addListener(Consumer<Boolean> l) {
            listeners.add(l);
        }
    }

    static final class FakeChannels implements NotificationChannels {
        int ensureCalls;

        @Override
        public void ensureChannels() {
            ensureCalls++;
        }

        @Override
        public ChannelState state(ChannelId id) {
            return new ChannelState(id.name(), true, true, 4, true);
        }

        @Override
        public String channelId(ChannelId id) {
            return id.name();
        }

        @Override
        public void ensureAlarmChannelBypass() {
        }
    }

    static final class FakeReadiness implements ReadinessChecker {
        @Override
        public List<ReadinessItem> check() {
            return List.of();
        }

        @Override
        public int missingRequiredCount() {
            return 0;
        }
    }

    static final class FakeIntents implements SystemSettingsIntents {
        @Override
        public Optional<android.content.Intent> forItem(ReadinessItemId id) {
            return Optional.empty();
        }

        @Override
        public android.content.Intent appNotificationSettings() {
            return null;
        }

        @Override
        public android.content.Intent appDetails() {
            return null;
        }

        @Override
        public Optional<android.content.Intent> oem(OemSettingId id) {
            return Optional.empty();
        }

        @Override
        public android.content.Intent dontKillMyApp(Manufacturer m) {
            return null;
        }
    }

    static class FakeFlags implements AppFlagsStore {
        private final CallLog log;
        volatile boolean disclaimerAccepted;
        volatile boolean onboardingCompleted;
        volatile boolean monitoringConfigured;

        FakeFlags(CallLog log) {
            this.log = log;
        }

        @Override
        public boolean disclaimerAccepted() {
            log.add("flags.disclaimerAccepted");
            return disclaimerAccepted;
        }

        @Override
        public void setDisclaimerAccepted() {
            log.add("flags.setDisclaimerAccepted");
            disclaimerAccepted = true;
        }

        @Override
        public boolean onboardingCompleted() {
            log.add("flags.onboardingCompleted");
            return onboardingCompleted;
        }

        @Override
        public void setOnboardingCompleted(boolean v) {
            log.add("flags.setOnboardingCompleted");
            onboardingCompleted = v;
        }

        @Override
        public boolean monitoringConfigured() {
            log.add("flags.monitoringConfigured");
            return monitoringConfigured;
        }

        @Override
        public void setMonitoringConfigured(boolean v) {
            log.add("flags.setMonitoringConfigured");
            monitoringConfigured = v;
        }

        @Override
        public long handledBootCount() {
            log.add("flags.handledBootCount");
            return 0;
        }

        @Override
        public void setHandledBootCount(long c) {
            log.add("flags.setHandledBootCount");
        }
    }

    static final class FakeReadings implements ReadingStore {
        private final CallLog log;

        FakeReadings(CallLog log) {
            this.log = log;
        }

        @Override
        public void upsert(List<Reading> r) {
            log.add("readings.upsert");
        }

        @Override
        public List<Reading> range(Instant from, Instant to) {
            log.add("readings.range");
            return List.of();
        }

        @Override
        public Optional<Reading> latestNotAfter(Instant t) {
            log.add("readings.latestNotAfter");
            return Optional.empty();
        }

        @Override
        public void replaceWindow(Instant from, Instant to, List<Reading> completeServerSet) {
            log.add("readings.replaceWindow");
        }

        @Override
        public void evictBefore(Instant t) {
            log.add("readings.evictBefore");
        }

        @Override
        public void clear() {
            log.add("readings.clear");
        }
    }

    static final class FakeEntries implements EntryStore {
        private final CallLog log;

        FakeEntries(CallLog log) {
            this.log = log;
        }

        @Override
        public void upsert(List<Entry> e) {
            log.add("entries.upsert");
        }

        @Override
        public void replaceWindow(EntryOrigin origin, Instant from, Instant to, List<Entry> completeServerSet) {
            log.add("entries.replaceWindow");
        }

        @Override
        public List<Entry> range(Instant from, Instant to) {
            log.add("entries.range");
            return List.of();
        }

        @Override
        public Optional<Entry> get(String id) {
            log.add("entries.get");
            return Optional.empty();
        }

        @Override
        public Optional<Entry> latestOfType(EntryType t) {
            log.add("entries.latestOfType");
            return Optional.empty();
        }

        @Override
        public void delete(String id) {
            log.add("entries.delete");
        }

        @Override
        public void evictBefore(Instant t) {
            log.add("entries.evictBefore");
        }

        @Override
        public void clear() {
            log.add("entries.clear");
        }
    }

    static final class FakeDeviceStatus implements DeviceStatusStore {
        private final CallLog log;

        FakeDeviceStatus(CallLog log) {
            this.log = log;
        }

        @Override
        public void upsert(List<DeviceStatus> d) {
            log.add("deviceStatus.upsert");
        }

        @Override
        public Optional<DeviceStatus> latest() {
            log.add("deviceStatus.latest");
            return Optional.empty();
        }

        @Override
        public void saveProfile(Profile p) {
            log.add("deviceStatus.saveProfile");
        }

        @Override
        public Optional<Profile> latestProfile() {
            log.add("deviceStatus.latestProfile");
            return Optional.empty();
        }

        @Override
        public void evictBefore(Instant t) {
            log.add("deviceStatus.evictBefore");
        }

        @Override
        public void clear() {
            log.add("deviceStatus.clear");
        }
    }

    static final class FakeSettings implements SettingsStore {
        private final CallLog log;
        final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        volatile Settings value = Settings.defaults();

        FakeSettings(CallLog log) {
            this.log = log;
        }

        @Override
        public Settings get() {
            log.add("settings.get");
            return value;
        }

        @Override
        public void put(Settings s) {
            log.add("settings.put");
            value = s;
            for (Runnable l : listeners) {
                l.run();
            }
        }

        @Override
        public void addListener(Runnable onChange) {
            listeners.add(onChange);
        }
    }

    static final class FakeConnection implements ConnectionStore {
        private final CallLog log;
        private final FakeComponents owner;

        FakeConnection(CallLog log, FakeComponents owner) {
            this.log = log;
            this.owner = owner;
        }

        @Override
        public Optional<String> baseUrl() {
            log.add("connection.baseUrl");
            return Optional.ofNullable(owner.storedUrl);
        }

        @Override
        public void save(String baseUrl, char[] token) {
            log.add("connection.save");
            owner.storedUrl = baseUrl;
        }

        @Override
        public void clear() {
            log.add("connection.clear");
        }

        @Override
        public Optional<char[]> token() {
            log.add("connection.token");
            return Optional.of("test-token".toCharArray());
        }
    }

    static final class FakeMonitorState implements MonitorStateStore {
        private final CallLog log;

        FakeMonitorState(CallLog log) {
            this.log = log;
        }

        @Override
        public MonitorState get() {
            log.add("monitorState.get");
            return MonitorState.initial();
        }

        @Override
        public void put(MonitorState s) {
            log.add("monitorState.put");
        }

        @Override
        public void clear() {
            log.add("monitorState.clear");
        }
    }

    static final class FakeAlarmState implements AlarmStateStore {
        private final CallLog log;

        FakeAlarmState(CallLog log) {
            this.log = log;
        }

        @Override
        public AlarmState get() {
            log.add("alarmState.get");
            return AlarmState.empty();
        }

        @Override
        public void put(AlarmState s) {
            log.add("alarmState.put");
        }

        @Override
        public void clear() {
            log.add("alarmState.clear");
        }
    }

    static final class FakeReminderState implements ReminderStateStore {
        private final CallLog log;

        FakeReminderState(CallLog log) {
            this.log = log;
        }

        @Override
        public Optional<ReminderState> get(ConsumableKind k) {
            log.add("reminderState.get");
            return Optional.empty();
        }

        @Override
        public void put(ReminderState s) {
            log.add("reminderState.put");
        }

        @Override
        public void clear() {
            log.add("reminderState.clear");
        }
    }

    static final class FakeNotifiedAlerts implements NotifiedAlertStore {
        private final CallLog log;

        FakeNotifiedAlerts(CallLog log) {
            this.log = log;
        }

        @Override
        public boolean contains(String entryId) {
            log.add("notifiedAlerts.contains");
            return false;
        }

        @Override
        public void add(String entryId, Instant entryTime) {
            log.add("notifiedAlerts.add");
        }

        @Override
        public void evictBefore(Instant t) {
            log.add("notifiedAlerts.evictBefore");
        }

        @Override
        public void clear() {
            log.add("notifiedAlerts.clear");
        }
    }

    /** Gateway that records {@code configure} and never does network work. */
    static final class FakeGateway implements NightscoutGateway {
        final List<String> configured = Collections.synchronizedList(new ArrayList<>());
        final List<String> configuredOn = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile TokenSource tokens;

        /** Never opens a socket: every call is recorded and reports NO_INTERNET. */
        private <T> Result<T> offline(String method) {
            calls.add(method);
            return Result.fail(app.lightscout.domain.port.FetchOutcome.NO_INTERNET,
                    app.lightscout.domain.port.DetailCode.NO_NETWORK);
        }

        @Override
        public void configure(String baseUrl, TokenSource tokens) {
            configuredOn.add(Thread.currentThread().getName());
            configured.add(baseUrl);
            this.tokens = tokens;
        }

        @Override
        public Result<ServerInfo> version(String baseUrl) {
            return offline("version");
        }

        @Override
        public Result<Permissions> permissions() {
            return offline("permissions");
        }

        @Override
        public Result<Map<NsCollection, Long>> lastModified() {
            return offline("lastModified");
        }

        @Override
        public Result<Page<Reading>> readingsSince(long fromMsExclusive, int limit) {
            return offline("readingsSince");
        }

        @Override
        public Result<Page<Entry>> meterBgSince(long fromMsExclusive, int limit) {
            return offline("meterBgSince");
        }

        @Override
        public Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit) {
            return offline("readingsWindow");
        }

        @Override
        public Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit) {
            return offline("meterBgWindow");
        }

        @Override
        public Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit) {
            return offline("entriesWindow");
        }

        @Override
        public Result<List<DeviceStatus>> deviceStatusSince(Instant from) {
            return offline("deviceStatusSince");
        }

        @Override
        public Result<Profile> latestProfile() {
            return offline("latestProfile");
        }

        @Override
        public Result<Page<Change>> history(NsCollection c, long cursorMs, int limit) {
            return offline("history");
        }

        @Override
        public Result<WriteAck> create(Entry e) {
            return offline("create");
        }

        @Override
        public Result<Entry> get(String id) {
            return offline("get");
        }

        @Override
        public Result<WriteAck> update(Entry e, long expectedSrvModifiedMs) {
            return offline("update");
        }

        @Override
        public Result<WriteAck> delete(String id) {
            return offline("delete");
        }
    }
}
