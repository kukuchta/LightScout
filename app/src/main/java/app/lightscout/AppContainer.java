package app.lightscout;

import android.content.Context;

import app.lightscout.alarm.AlarmRuntime;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.SupportPromptStore;
import app.lightscout.usecases.update.UpdateService;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.PumpAlertNotifier;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.monitor.MonitorBootDependencies;
import app.lightscout.monitor.MonitorRuntime;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.ui.core.AppInfo;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.widget.GlucoseWidgetUpdater;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.SyncCycle;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import app.lightscout.usecases.alarm.DefaultAlarmActions;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.common.ServerEpoch;
import app.lightscout.usecases.common.SettingsWriter;
import app.lightscout.usecases.connection.ConnectionStateReset;
import app.lightscout.usecases.connection.DefaultConnectionService;
import app.lightscout.usecases.connection.OriginBoundTokenSource;
import app.lightscout.usecases.entry.DefaultEntryWriter;
import app.lightscout.usecases.history.DefaultHistoryLoader;
import app.lightscout.usecases.onboarding.DefaultOnboardingState;
import app.lightscout.usecases.settings.DefaultSettingsService;
import app.lightscout.usecases.status.DefaultStatusRepository;
import app.lightscout.usecases.status.StatusProjector;
import app.lightscout.usecases.sync.DefaultSyncCycle;
import app.lightscout.usecases.sync.SyncPorts;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The composition root (CMP-80, CP-15 §8.1, ADR-010, ADR-068, R-6): one instance per process, held by
 * {@link LightScoutApplication}. Manual DI only — every component is created by {@link ContainerComponents} or by a
 * constructor call here, never by reflection.
 *
 * <p><b>Direct boot</b>: the constructor builds only the locked-safe tier — the IF-66 logger (no-op for the CE ring
 * buffer while locked), IF-15 clock, the CMP-26 channel registry and a deferred IF-60 flag store whose DE store is
 * created on its first call — plus the receiver executor that {@code :monitor} needs while locked (SS-09 §5). Every
 * other component is built <b>lazily on first use</b>, at most once, and only after the first unlock: a request while
 * the user is locked fails with {@link IllegalStateException} before anything is built.
 *
 * <p><b>No disk on the main thread</b>: building the graph never touches storage. Stores are wired as
 * {@link DeferredStores} (created on their first call, which is always off the main thread) and the monitoring
 * gateway is configured on the monitor thread ({@link MonitoringConnection}). So {@code MonitorService},
 * {@code AlarmActivity} and ViewModel factories may obtain components on the main thread (SS-09 §5.4, SS-10 §5.2).
 *
 * <p><b>Threads</b> (ADR-024, R-34, CP-15 §8.2): exactly two executors serve the app — {@code monitorExecutor}
 * (single thread, the only writer of IF-13 / IF-14 / IF-63 / IF-64) and {@code ioExecutor} (3 threads). The extra
 * single-thread {@code receiverExecutor} serves only the {@code :monitor} receivers after {@code goAsync()} (SS-09 §5:
 * it must not be the monitor thread).
 *
 * <p><b>Cycles</b> are broken with {@link Supplier}s: {@code MonitorRuntime} gets {@code Supplier<SyncCycle>} /
 * {@code Supplier<StatusRepository>}; {@code AlarmRuntime} gets {@code Supplier<AlarmActions>}.
 *
 * <p>It implements IF-28 {@link Dependencies} (CP-00A R-12).
 */
public final class AppContainer implements Dependencies {

    private static final int IO_THREADS = 3;

    private final ContainerComponents components;
    private final Clock clock;
    private final Logger logger;
    private final NotificationChannels channels;
    private final AppFlagsStore flags;
    private final ExecutorService receiverExecutor;
    private final MonitorBootDependencies bootDependencies;
    private final AppInfo appInfo;
    private final Map<Class<?>, Supplier<?>> registry;

    private volatile Thread monitorThread;

    // Lazily built after unlock; guarded by "this".
    private ExecutorService monitorExecutor;
    private ExecutorService ioExecutor;
    private Connectivity connectivity;
    private ReadinessChecker readinessChecker;
    private SystemSettingsIntents systemSettingsIntents;
    private ManufacturerDetector manufacturerDetector;
    private Stores stores;
    private AlarmStateMemory alarmMemory;
    private MonitorStateMemory monitorMemory;
    private ServerEpoch serverEpoch;
    private SettingsWriter settingsWriter;
    private OriginBoundTokenSource tokens;
    private NightscoutGateway monitoringGateway;
    private NightscoutGateway probeGateway;
    private MonitoringConnection monitoringConnection;
    private ReminderScheduler reminderScheduler;
    private PumpAlertNotifier pumpAlertNotifier;
    private ContainerComponents.MonitorRuntimeHandle monitorRuntime;
    private ContainerComponents.AlarmRuntimeHandle alarmRuntime;
    private StatusProjector statusProjector;
    private DefaultStatusRepository statusRepository;
    private DefaultSyncCycle syncCycle;
    private DefaultAlarmActions alarmActions;
    private DefaultSettingsService settingsService;
    private DefaultOnboardingState onboardingState;
    private ConnectionStateReset connectionStateReset;
    private DefaultConnectionService connectionService;
    private DefaultEntryWriter entryWriter;
    private EntryRules entryRules;
    private SupportPromptStore supportPrompt;
    private UpdateService updateService;
    private DefaultHistoryLoader historyLoader;
    private GlucoseWidgetUpdater glucoseWidgetUpdater;

    /** The deferred store adapters (created together; each real store is created on its first call). */
    private record Stores(ReadingStore readings, EntryStore entries, DeviceStatusStore deviceStatus,
                          SettingsStore settings, ConnectionStore connection, MonitorStateStore monitorState,
                          AlarmStateStore alarmState, ReminderStateStore reminderState,
                          NotifiedAlertStore notifiedAlerts) {
    }

    /**
     * Builds the locked-safe tier only (CP-15 §8.1).
     *
     * @param components the leaf-adapter factory
     */
    public AppContainer(ContainerComponents components) {
        this.components = Objects.requireNonNull(components, "components");
        this.clock = components.clock();
        this.logger = components.logger();
        this.channels = components.channels();
        this.flags = new DeferredStores.AppFlags(components::appFlags);
        this.receiverExecutor = Executors.newSingleThreadExecutor(named("lightscout-receiver", null));
        this.appInfo = new AppInfo(components.versionName());
        this.bootDependencies = new BootDependencies();
        this.registry = buildRegistry();
    }

    // ------------------------------------------------------------------ locked-safe tier

    /**
     * Returns IF-66 (locked-safe).
     *
     * @return the logger
     */
    public Logger logger() {
        return logger;
    }

    /**
     * Returns IF-15 (locked-safe).
     *
     * @return the clock
     */
    public Clock clock() {
        return clock;
    }

    /**
     * Returns CMP-26 (locked-safe).
     *
     * @return the channel registry
     */
    public NotificationChannels channels() {
        return channels;
    }

    /**
     * Returns IF-60; its DE store is created on the first call (locked-safe; CE methods must not be called while
     * locked — R-39).
     *
     * @return the flag store
     */
    public AppFlagsStore flags() {
        return flags;
    }

    /**
     * Returns the direct-boot-safe collaborators of {@code :monitor} (SS-09 §5.2).
     *
     * @return boot dependencies
     */
    public MonitorBootDependencies monitorBootDependencies() {
        return bootDependencies;
    }

    // ------------------------------------------------------------------ IF-28

    /** {@inheritDoc} */
    @Override
    public <T> T get(Class<T> type) {
        Supplier<?> supplier = registry.get(type);
        if (supplier == null) {
            throw new IllegalStateException("No dependency registered for " + type.getName() + " (CP-00A R-12)");
        }
        return type.cast(supplier.get());
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Executor ioExecutor() {
        if (ioExecutor == null) {
            requireUnlocked("ioExecutor");
            ioExecutor = Executors.newFixedThreadPool(IO_THREADS, named("lightscout-io-", new AtomicInteger()));
        }
        return ioExecutor;
    }

    /** {@inheritDoc} */
    @Override
    public AppInfo appInfo() {
        return appInfo;
    }

    private Map<Class<?>, Supplier<?>> buildRegistry() {
        Map<Class<?>, Supplier<?>> map = new LinkedHashMap<>();
        map.put(StatusRepository.class, this::statusRepository);       // IF-21
        map.put(HistoryLoader.class, this::historyLoader);             // IF-22
        map.put(EntryWriter.class, this::entryWriter);                 // IF-23
        map.put(ConnectionService.class, this::connectionService);     // IF-24
        map.put(AlarmActions.class, this::alarmActions);               // IF-25
        map.put(SettingsService.class, this::settingsService);         // IF-26
        map.put(OnboardingState.class, this::onboardingState);         // IF-27
        map.put(ReadinessChecker.class, this::readinessChecker);       // CMP-27
        map.put(NotificationChannels.class, this::channels);           // CMP-26
        map.put(SystemSettingsIntents.class, this::systemSettingsIntents); // CMP-28
        map.put(ManufacturerDetector.class, this::manufacturerDetector);   // CMP-28
        map.put(Clock.class, this::clock);                             // IF-15
        map.put(EntryRules.class, this::entryRules);                   // IF-06 (S-02)
        map.put(SupportPromptStore.class, this::supportPrompt);        // DCR-46 (S-01)
        map.put(UpdateService.class, this::updateService);             // DCR-49 (S-01, S-04)
        return Collections.unmodifiableMap(map);
    }

    // ------------------------------------------------------------------ executors

    /**
     * Returns the single monitor thread (R-34).
     *
     * @return {@code monitorExecutor}
     */
    public synchronized Executor monitorExecutor() {
        if (monitorExecutor == null) {
            requireUnlocked("monitorExecutor");
            monitorExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "lightscout-monitor");
                monitorThread = thread;
                return thread;
            });
        }
        return monitorExecutor;
    }

    private boolean onMonitorThread() {
        return Thread.currentThread() == monitorThread;
    }

    // ------------------------------------------------------------------ platform (after unlock)

    /**
     * Returns the single process-wide IF-19 (SS-07 A-2: one {@code AndroidConnectivity}, after unlock).
     *
     * @return connectivity
     */
    public synchronized Connectivity connectivity() {
        if (connectivity == null) {
            requireUnlocked("Connectivity");
            connectivity = components.connectivity();
        }
        return connectivity;
    }

    /**
     * Returns CMP-27 (also IF-65 for the projector).
     *
     * @return the readiness checker
     */
    public synchronized ReadinessChecker readinessChecker() {
        if (readinessChecker == null) {
            requireUnlocked("ReadinessChecker");
            readinessChecker = components.readinessChecker(channels);
        }
        return readinessChecker;
    }

    /**
     * Returns CMP-28 settings intents.
     *
     * @return the settings intents
     */
    public synchronized SystemSettingsIntents systemSettingsIntents() {
        if (systemSettingsIntents == null) {
            requireUnlocked("SystemSettingsIntents");
            systemSettingsIntents = components.systemSettingsIntents(channels);
        }
        return systemSettingsIntents;
    }

    /**
     * Returns CMP-28 manufacturer detection.
     *
     * @return the detector
     */
    public synchronized ManufacturerDetector manufacturerDetector() {
        if (manufacturerDetector == null) {
            requireUnlocked("ManufacturerDetector");
            manufacturerDetector = components.manufacturerDetector();
        }
        return manufacturerDetector;
    }

    // ------------------------------------------------------------------ stores and shared memories

    private synchronized Stores stores() {
        if (stores == null) {
            requireUnlocked("stores");
            stores = new Stores(
                    new DeferredStores.Readings(components::readings),
                    new DeferredStores.Entries(components::entries),
                    new DeferredStores.DeviceStatuses(components::deviceStatus),
                    new DeferredStores.SettingsPort(components::settings),
                    new DeferredStores.Connection(components::connection),
                    new DeferredStores.MonitorStates(components::monitorState),
                    new DeferredStores.AlarmStates(components::alarmState),
                    new DeferredStores.ReminderStates(components::reminderState),
                    new DeferredStores.NotifiedAlerts(components::notifiedAlerts));
        }
        return stores;
    }

    /**
     * Returns the one shared in-memory ENT-08 (SS-05: projector, sync cycle, IF-25, alarm runtime, R-71 reset).
     *
     * @return the alarm state memory
     */
    synchronized AlarmStateMemory alarmStateMemory() {
        if (alarmMemory == null) {
            requireUnlocked("AlarmStateMemory");
            alarmMemory = new AlarmStateMemory();
        }
        return alarmMemory;
    }

    /**
     * Returns the one shared in-memory ENT-07 (SS-05: projector, sync cycle, history loader, R-71 reset).
     *
     * @return the monitor state memory
     */
    synchronized MonitorStateMemory monitorStateMemory() {
        if (monitorMemory == null) {
            requireUnlocked("MonitorStateMemory");
            monitorMemory = new MonitorStateMemory();
        }
        return monitorMemory;
    }

    /**
     * Returns the one shared server epoch (SS-06: R-71 reset, history loader, entry writer).
     *
     * @return the server epoch
     */
    synchronized ServerEpoch serverEpoch() {
        if (serverEpoch == null) {
            requireUnlocked("ServerEpoch");
            serverEpoch = new ServerEpoch();
        }
        return serverEpoch;
    }

    /**
     * Returns the one shared settings writer (SS-06 IR-44: IF-25 and IF-26).
     *
     * @return the settings writer
     */
    synchronized SettingsWriter settingsWriter() {
        if (settingsWriter == null) {
            settingsWriter = new SettingsWriter(stores().settings());
        }
        return settingsWriter;
    }

    // ------------------------------------------------------------------ gateways (Q-46: two instances)

    /**
     * Returns the one shared origin-bound token source over IF-12 (IR-45): monitoring gateway, entry writer and the
     * R-71 reset.
     *
     * @return the token source
     */
    synchronized OriginBoundTokenSource tokens() {
        if (tokens == null) {
            tokens = new OriginBoundTokenSource(stores().connection());
        }
        return tokens;
    }

    /**
     * Returns the monitoring gateway (sync cycle, history loader, entry writer, R-71 reset). Its start-up
     * configuration ({@code bind(storedUrl)} + {@code configure}) runs on the monitor thread (see
     * {@link MonitoringConnection}).
     *
     * @return the monitoring gateway
     */
    synchronized NightscoutGateway monitoringGateway() {
        if (monitoringGateway == null) {
            requireUnlocked("monitoring NightscoutGateway");
            Connectivity c = connectivity();
            // BUG-14: the OkHttpClient (SSLContext init) is built on the first gateway call, off the main thread
            monitoringGateway = new DeferredGateway(() -> components.gateway(c));
            monitoringConnection = new MonitoringConnection(monitoringGateway, tokens(), stores().connection());
            // Never inline under the container lock (it reads CE storage); a tick that needs the gateway earlier
            // runs the same idempotent step itself (syncCycleOnMonitorThread).
            monitorExecutor().execute(monitoringConnection::ensureConfigured);
        }
        return monitoringGateway;
    }

    /**
     * Returns {@code ioExecutor} for the users of the monitoring gateway on io threads (IF-22, IF-23): before every
     * task the idempotent start-up configuration ({@link MonitoringConnection#ensureConfigured()}) is made sure of, so
     * an io-thread first use never meets an unbound token source or an unconfigured gateway (IR-107). The step itself
     * always runs on {@code monitorExecutor}, the single writer, so it is serialised with the R-71 reset and can never
     * overwrite a newer configuration (IR-118, Q-46): the io task submits it there and waits (io may block; the main
     * thread never does).
     *
     * @return the configuring io executor
     */
    synchronized Executor monitoringIo() {
        Executor io = ioExecutor();
        monitoringGateway();
        MonitoringConnection connection = monitoringConnection;
        return task -> io.execute(() -> {
            configureOnMonitorThread(connection);
            task.run();
        });
    }

    /**
     * Runs the monitoring gateway's start-up configuration on the monitor thread and waits for it (io callers only).
     * Returns at once when it is already configured or when called on the monitor thread itself.
     *
     * @param connection the start-up step
     */
    private void configureOnMonitorThread(MonitoringConnection connection) {
        if (connection.isConfigured()) {
            return;
        }
        if (onMonitorThread()) {
            connection.ensureConfigured();
            return;
        }
        FutureTask<Void> step = new FutureTask<>(connection::ensureConfigured, null);
        monitorExecutor().execute(step);
        try {
            step.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // ensureConfigured never throws; a failure leaves it unconfigured and the use case reports the outcome
        }
    }

    /**
     * Returns the probe gateway: used only by IF-24 for the S-06 checks, with its own JWT holder (Q-46).
     *
     * @return the probe gateway
     */
    synchronized NightscoutGateway probeGateway() {
        if (probeGateway == null) {
            requireUnlocked("probe NightscoutGateway");
            Connectivity c = connectivity();
            // BUG-14: built on the first IF-24 check (ioExecutor), never in ConnectionFragment.onCreate (main)
            probeGateway = new DeferredGateway(() -> components.gateway(c));
        }
        return probeGateway;
    }

    private synchronized ReminderScheduler reminderScheduler() {
        if (reminderScheduler == null) {
            reminderScheduler = components.reminderScheduler();
        }
        return reminderScheduler;
    }

    private synchronized PumpAlertNotifier pumpAlertNotifier() {
        if (pumpAlertNotifier == null) {
            pumpAlertNotifier = components.pumpAlertNotifier();
        }
        return pumpAlertNotifier;
    }

    // ------------------------------------------------------------------ runtimes (CP-08, CP-07)

    private synchronized ContainerComponents.MonitorRuntimeHandle monitorRuntimeHandle() {
        if (monitorRuntime == null) {
            requireUnlocked("MonitorRuntime");
            Stores s = stores();
            monitorRuntime = components.monitorRuntime(new MonitorRuntime.Collaborators(
                    this::syncCycleOnMonitorThread, this::statusRepository,
                    s.monitorState(), s.alarmState(), s.settings(), flags, channels, connectivity(),
                    clock, logger, monitorExecutor(), this::historyLoader));
        }
        return monitorRuntime;
    }

    /**
     * Returns the one {@code MonitorRuntime} of this process (SS-09 §5.3), built after unlock.
     *
     * @return the runtime
     */
    public MonitorRuntime monitorRuntime() {
        return monitorRuntimeHandle().runtime();
    }

    /**
     * Returns IF-62 (the runtime's control).
     *
     * @return monitor control
     */
    public MonitorControl monitorControl() {
        return monitorRuntimeHandle().control();
    }

    private synchronized ContainerComponents.AlarmRuntimeHandle alarmRuntimeHandle() {
        if (alarmRuntime == null) {
            requireUnlocked("AlarmRuntime");
            alarmRuntime = components.alarmRuntime(this::alarmActions, stores().alarmState(), alarmStateMemory(),
                    channels, monitorExecutor());
        }
        return alarmRuntime;
    }

    /**
     * Returns the one {@code AlarmRuntime} of this process (SS-10 §5.1) — the same instance as IF-16.
     *
     * @return the runtime
     */
    public AlarmRuntime alarmRuntime() {
        return alarmRuntimeHandle().runtime();
    }

    /**
     * Returns the alarm type S-09 currently shows according to the alarm runtime's live screen state, or
     * {@code null} (IR-109, Moderator decision: {@code MainActivity.onResume} hands off to S-09 on this state, not on
     * the IF-21 snapshot, which still says „ringing” for a moment after Snooze).
     *
     * @return the live alarm type or {@code null}
     */
    public AlarmType liveAlarmScreen() {
        return alarmRuntimeHandle().liveScreen().get();
    }

    /**
     * Returns IF-16 (the alarm runtime).
     *
     * @return alarm controller
     */
    AlarmController alarmController() {
        return alarmRuntimeHandle().controller();
    }

    // ------------------------------------------------------------------ use cases (CP-02)

    private synchronized StatusProjector statusProjector() {
        if (statusProjector == null) {
            Stores s = stores();
            statusProjector = new StatusProjector(s.readings(), s.entries(), s.deviceStatus(), s.monitorState(),
                    s.settings(), s.alarmState(), connectivity(), readinessChecker(), flags, clock,
                    alarmStateMemory(), monitorStateMemory(), logger);
        }
        return statusProjector;
    }

    /**
     * Returns IF-21.
     *
     * @return the status repository
     */
    public StatusRepository statusRepository() {
        return defaultStatusRepository();
    }

    /**
     * Returns whether credential-encrypted storage is available without opening it.
     */
    public boolean isUserUnlocked() {
        return components.isUserUnlocked();
    }

    /**
     * Returns the process-wide S-12 updater, created only after user unlock.
     *
     * @param context application context
     * @return the widget updater
     */
    public synchronized GlucoseWidgetUpdater glucoseWidgetUpdater(Context context) {
        requireUnlocked("GlucoseWidgetUpdater");
        if (glucoseWidgetUpdater == null) {
            glucoseWidgetUpdater = new GlucoseWidgetUpdater(context, statusRepository(), historyLoader(), clock,
                    monitorExecutor());
        }
        return glucoseWidgetUpdater;
    }

    private synchronized DefaultStatusRepository defaultStatusRepository() {
        if (statusRepository == null) {
            statusRepository = new DefaultStatusRepository(statusProjector(), connectivity(), stores().settings(),
                    monitorExecutor(), monitorControl(), clock);
        }
        return statusRepository;
    }

    /**
     * Returns IF-20 (called by {@code :monitor} on the monitor thread only).
     *
     * @return the sync cycle
     */
    synchronized SyncCycle syncCycle() {
        if (syncCycle == null) {
            Stores s = stores();
            SyncPorts ports = new SyncPorts(monitoringGateway(), s.connection(), s.readings(), s.entries(),
                    s.deviceStatus(), s.settings(), s.monitorState(), s.alarmState(), s.reminderState(),
                    s.notifiedAlerts(), alarmController(), reminderScheduler(), pumpAlertNotifier(), clock, logger);
            syncCycle = new DefaultSyncCycle(ports, statusProjector(), defaultStatusRepository(), alarmStateMemory(),
                    monitorStateMemory());
        }
        return syncCycle;
    }

    /**
     * The {@code Supplier<SyncCycle>} given to {@code MonitorRuntime}: resolved on the monitor thread inside a tick,
     * so it also makes sure the monitoring gateway's start-up configuration ran before the tick uses it.
     */
    private SyncCycle syncCycleOnMonitorThread() {
        SyncCycle cycle = syncCycle();
        MonitoringConnection connection;
        synchronized (this) {
            connection = monitoringConnection;
        }
        // :monitor resolves the supplier on the monitor thread only (SS-09 §5.3); anywhere else the task posted by
        // monitoringGateway() does the work (never storage access on another thread's behalf here).
        if (connection != null && onMonitorThread()) {
            connection.ensureConfigured();
        }
        return cycle;
    }

    /**
     * Returns IF-25.
     *
     * @return alarm actions
     */
    public synchronized AlarmActions alarmActions() {
        if (alarmActions == null) {
            alarmActions = new DefaultAlarmActions(monitorExecutor(), settingsWriter(), stores().alarmState(),
                    alarmStateMemory(), alarmController(), syncCycle(), monitorControl(), clock, logger);
        }
        return alarmActions;
    }

    /**
     * Returns IF-26.
     *
     * @return the settings service
     */
    public synchronized SettingsService settingsService() {
        if (settingsService == null) {
            settingsService = new DefaultSettingsService(stores().settings(), settingsWriter(), monitorControl());
        }
        return settingsService;
    }

    /**
     * Returns IF-27.
     *
     * @return the onboarding state
     */
    public synchronized OnboardingState onboardingState() {
        if (onboardingState == null) {
            requireUnlocked("OnboardingState");
            onboardingState = new DefaultOnboardingState(flags, stores().connection(), monitorControl());
        }
        return onboardingState;
    }

    private synchronized ConnectionStateReset connectionStateReset() {
        if (connectionStateReset == null) {
            Stores s = stores();
            connectionStateReset = new ConnectionStateReset(monitoringGateway(), tokens(), serverEpoch(),
                    s.readings(), s.entries(), s.deviceStatus(), s.monitorState(), s.alarmState(), s.reminderState(),
                    s.notifiedAlerts(), reminderScheduler(), alarmStateMemory(), monitorStateMemory(),
                    monitorControl());
        }
        return connectionStateReset;
    }

    /**
     * Returns IF-24 (probe gateway for the checks; monitoring gateway only through the R-71 reset).
     *
     * @return the connection service
     */
    public synchronized ConnectionService connectionService() {
        if (connectionService == null) {
            connectionService = new DefaultConnectionService(ioExecutor(), monitorExecutor(), probeGateway(),
                    stores().connection(), connectionStateReset());
        }
        return connectionService;
    }

    /**
     * Returns IF-23.
     *
     * @return the entry writer
     */
    public synchronized EntryWriter entryWriter() {
        if (entryWriter == null) {
            entryWriter = new DefaultEntryWriter(monitoringIo(), monitoringGateway(), stores().entries(),
                    connectivity(), monitorControl(), clock, logger, serverEpoch(), tokens());
        }
        return entryWriter;
    }

    /**
     * Returns the one-time support prompt store (DCR-46). One shared instance, created on first use; blocking, call
     * off the main thread.
     *
     * @return the support prompt store
     */
    public synchronized SupportPromptStore supportPrompt() {
        if (supportPrompt == null) {
            supportPrompt = components.supportPrompt();
        }
        return supportPrompt;
    }

    /**
     * Returns the update check (DCR-49): the daily GitHub check and the S-01 dialog rule. One shared instance,
     * created on first use; its methods are blocking, call them off the main thread.
     *
     * @return the update service
     */
    public synchronized UpdateService updateService() {
        if (updateService == null) {
            updateService = new UpdateService(components.updateStore(), components.updateSource(),
                    components.versionName());
        }
        return updateService;
    }

    /**
     * Returns IF-06 (pure entry rules; S-02 validation and similar-entry check). One shared instance.
     *
     * @return the entry rules
     */
    public synchronized EntryRules entryRules() {
        if (entryRules == null) {
            entryRules = new EntryRules();
        }
        return entryRules;
    }

    /**
     * Returns IF-22.
     *
     * @return the history loader
     */
    public synchronized HistoryLoader historyLoader() {
        if (historyLoader == null) {
            Stores s = stores();
            historyLoader = new DefaultHistoryLoader(monitoringIo(), monitorExecutor(), monitoringGateway(),
                    s.readings(), s.entries(), s.deviceStatus(), s.monitorState(), monitorStateMemory(),
                    connectivity(), clock, serverEpoch());
        }
        return historyLoader;
    }

    /**
     * Returns the F5 boot-count check task for the APP_OPEN origin (SS-09 §5.5; never from
     * {@code Application.onCreate}).
     *
     * @return the task (run off the main thread)
     */
    public Runnable bootCountCheck() {
        return components.bootCountCheck(flags);
    }

    // ------------------------------------------------------------------ helpers

    private void requireUnlocked(String what) {
        if (!components.isUserUnlocked()) {
            throw new IllegalStateException(what + " requested while the user is locked; only the flag store, the "
                    + "channel registry and the logger exist before unlock (CP-15 §8.1, CP-00 §6)");
        }
    }

    private static ThreadFactory named(String name, AtomicInteger counter) {
        return runnable -> new Thread(runnable, counter == null ? name : name + counter.incrementAndGet());
    }

    /** {@link MonitorBootDependencies} over the locked-safe tier (SS-09 §5.2). */
    private final class BootDependencies implements MonitorBootDependencies {

        @Override
        public AppFlagsStore flags() {
            return flags;
        }

        @Override
        public NotificationChannels channels() {
            return channels;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public Clock clock() {
            return clock;
        }

        @Override
        public Executor receiverExecutor() {
            return receiverExecutor;
        }
    }
}
