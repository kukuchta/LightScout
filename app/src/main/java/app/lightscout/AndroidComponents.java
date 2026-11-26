package app.lightscout;

import android.content.Context;
import android.os.UserManager;

import app.lightscout.alarm.AlarmRuntime;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.PumpAlertNotifier;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.SupportPromptStore;
import app.lightscout.domain.port.UpdateSource;
import app.lightscout.domain.port.AlarmSoundStore;
import app.lightscout.domain.port.AudioSourcePort;
import app.lightscout.domain.port.UpdateStore;
import app.lightscout.nightscout.GitHubReleaseSource;
import app.lightscout.monitor.BootCountCheck;
import app.lightscout.monitor.MonitorNotifications;
import app.lightscout.monitor.MonitorRuntime;
import app.lightscout.nightscout.OkHttpNightscoutGateway;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.platform.channels.AndroidChannelBackend;
import app.lightscout.platform.channels.DefaultNotificationChannels;
import app.lightscout.platform.clock.AndroidClock;
import app.lightscout.platform.connectivity.AndroidConnectivity;
import app.lightscout.platform.intents.AndroidSystemSettingsIntents;
import app.lightscout.platform.log.PlatformLogger;
import app.lightscout.platform.manufacturer.BuildManufacturerDetector;
import app.lightscout.platform.readiness.AndroidReadinessProbe;
import app.lightscout.platform.readiness.DefaultReadinessChecker;
import app.lightscout.platform.sound.AndroidAudioSource;
import app.lightscout.storage.StorageFactory;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Android implementation of {@link ContainerComponents}: the only place that names the {@code :storage},
 * {@code :nightscout} and {@code :platform} implementation classes (R-2) — wiring per SS-03 §1, SS-04 §1, SS-07 A-2,
 * SS-09 §5, SS-10 §5.
 *
 * <p>The constructor creates only the clock and the logger (both locked-safe; the logger's CE ring buffer stays a
 * no-op while locked). {@link StorageFactory} is created on the first store request; creating it touches no storage
 * (SS-03 §1).
 */
final class AndroidComponents implements ContainerComponents {

    private final Context app;
    private final String versionName;
    private final Clock clock;
    private final Logger logger;
    private StorageFactory storage;

    /**
     * Creates the factory.
     *
     * @param context     any context (the application context is kept)
     * @param debugBuild  {@code BuildConfig.DEBUG} (debug logging to logcat only in debug builds)
     * @param versionName {@code BuildConfig.VERSION_NAME}
     */
    AndroidComponents(Context context, boolean debugBuild, String versionName) {
        this.app = context.getApplicationContext();
        this.versionName = versionName;
        this.clock = new AndroidClock();
        this.logger = PlatformLogger.create(app, clock, debugBuild);
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
        return new DefaultNotificationChannels(new AndroidChannelBackend(app), logger);
    }

    @Override
    public AppFlagsStore appFlags() {
        return storage().appFlags();
    }

    @Override
    public boolean isUserUnlocked() {
        UserManager userManager = app.getSystemService(UserManager.class);
        return userManager != null && userManager.isUserUnlocked();
    }

    @Override
    public String versionName() {
        return versionName;
    }

    @Override
    public Connectivity connectivity() {
        return new AndroidConnectivity(app, logger);
    }

    @Override
    public ReadinessChecker readinessChecker(NotificationChannels channels) {
        return new DefaultReadinessChecker(new AndroidReadinessProbe(app, channels));
    }

    @Override
    public SystemSettingsIntents systemSettingsIntents(NotificationChannels channels) {
        return new AndroidSystemSettingsIntents(app, channels);
    }

    @Override
    public ManufacturerDetector manufacturerDetector() {
        return new BuildManufacturerDetector();
    }

    @Override
    public ReadingStore readings() {
        return storage().readings();
    }

    @Override
    public EntryStore entries() {
        return storage().entries();
    }

    @Override
    public DeviceStatusStore deviceStatus() {
        return storage().deviceStatus();
    }

    @Override
    public SettingsStore settings() {
        return storage().settings();
    }

    @Override
    public ConnectionStore connection() {
        return storage().connection();
    }

    @Override
    public MonitorStateStore monitorState() {
        return storage().monitorState();
    }

    @Override
    public AlarmStateStore alarmState() {
        return storage().alarmState();
    }

    @Override
    public ReminderStateStore reminderState() {
        return storage().reminderState();
    }

    @Override
    public SupportPromptStore supportPrompt() {
        return storage().supportPrompt();
    }

    @Override
    public UpdateStore updateStore() {
        return storage().updateStore();
    }

    @Override
    public AlarmSoundStore alarmSoundStore() {
        return storage().alarmSounds();
    }

    @Override
    public AudioSourcePort audioSource() {
        return new AndroidAudioSource(app);
    }

    @Override
    public UpdateSource updateSource() {
        return new GitHubReleaseSource();
    }

    @Override
    public NotifiedAlertStore notifiedAlerts() {
        return storage().notifiedAlerts();
    }

    @Override
    public NightscoutGateway gateway(Connectivity connectivity) {
        return new OkHttpNightscoutGateway(clock, connectivity, logger);
    }

    @Override
    public ReminderScheduler reminderScheduler() {
        return MonitorNotifications.reminderScheduler(app, channels());
    }

    @Override
    public PumpAlertNotifier pumpAlertNotifier() {
        return MonitorNotifications.pumpAlertNotifier(app, channels());
    }

    @Override
    public MonitorRuntimeHandle monitorRuntime(MonitorRuntime.Collaborators collaborators) {
        MonitorRuntime runtime = new MonitorRuntime(app, collaborators);
        return new MonitorRuntimeHandle(runtime, runtime.control());
    }

    @Override
    public AlarmRuntimeHandle alarmRuntime(Supplier<AlarmActions> alarmActions, AlarmStateStore alarmStateStore,
                                           AlarmStateMemory alarmMemory, NotificationChannels channels,
                                           Executor monitorExecutor) {
        // DCR-51: the sound preferences open on the first alarm start, on the player thread, not while this is wired
        AlarmRuntime runtime = new AlarmRuntime(app, alarmActions, alarmStateStore, alarmMemory, channels, clock,
                logger, monitorExecutor, new DeferredStores.SoundResolver(() -> storage().alarmSoundResolver()));
        return new AlarmRuntimeHandle(runtime, runtime, () -> {
            AlarmView view = runtime.screen().current();
            return view == null ? null : view.type();
        });
    }

    @Override
    public Runnable bootCountCheck(AppFlagsStore flags) {
        return () -> new BootCountCheck(app, flags, logger).run();
    }

    private synchronized StorageFactory storage() {
        if (storage == null) {
            storage = new StorageFactory(app, clock, logger);
        }
        return storage;
    }
}
