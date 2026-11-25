package app.lightscout;

import app.lightscout.alarm.AlarmRuntime;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
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
import app.lightscout.domain.port.SupportPromptStore;
import app.lightscout.domain.port.UpdateSource;
import app.lightscout.domain.port.UpdateStore;
import app.lightscout.monitor.MonitorRuntime;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Creates the leaf adapters that {@link AppContainer} wires (CP-15 §4.2): platform services, stores, gateways and
 * the {@code :monitor} / {@code :alarm} runtimes. Every method <b>creates</b> a new object; {@link AppContainer}
 * decides when (lazily, at most once per process) and wires the use cases between them (manual DI, R-6, ADR-010).
 *
 * <p>The Android implementation is {@link AndroidComponents}. JVM tests use a counting fake to prove the container
 * laziness rule (CP-15 §9 „Container laziness”: only the logger, the channel registry and the DE flag store may be
 * built while the user is locked).
 */
public interface ContainerComponents {

    /**
     * Handle of the process-wide {@code :monitor} runtime.
     *
     * @param runtime the runtime returned by {@code MonitorRuntimeProvider.monitorRuntime()}
     * @param control its IF-62 implementation ({@code runtime.control()})
     */
    record MonitorRuntimeHandle(MonitorRuntime runtime, MonitorControl control) {
    }

    /**
     * Handle of the process-wide {@code :alarm} runtime.
     *
     * @param runtime    the runtime returned by {@code AlarmRuntimeProvider.alarmRuntime()}
     * @param controller the same instance as IF-16 {@link AlarmController}
     * @param liveScreen the alarm type S-09 currently shows ({@code runtime.screen().current()}), or {@code null}
     *                   when no alarm screen is live (IR-109: source of the {@code MainActivity.onResume} hand-off)
     */
    record AlarmRuntimeHandle(AlarmRuntime runtime, AlarmController controller, Supplier<AlarmType> liveScreen) {
    }

    // ------------------------------------------------------------------ locked-safe (CP-15 §8.1)

    /**
     * Returns the IF-15 clock (one instance; safe while locked).
     *
     * @return the clock
     */
    Clock clock();

    /**
     * Returns the IF-66 logger (one instance; a no-op for the CE ring buffer while locked, never throws).
     *
     * @return the logger
     */
    Logger logger();

    /**
     * Creates the CMP-26 channel registry (safe while locked).
     *
     * @return the channel registry
     */
    NotificationChannels channels();

    /**
     * Creates the IF-60 flag store. Only its device-protected part is opened here (safe while locked).
     *
     * @return the flag store
     */
    AppFlagsStore appFlags();

    /**
     * Tells whether credential-encrypted storage is available ({@code UserManager.isUserUnlocked()}).
     *
     * @return {@code true} after the first unlock
     */
    boolean isUserUnlocked();

    /**
     * Returns the {@code versionName} of the build (S-04 „Version {x.y.z}”).
     *
     * @return the SemVer version name
     */
    String versionName();

    // ------------------------------------------------------------------ after unlock

    /**
     * Creates the single process-wide IF-19 connectivity port (registers its network callback).
     *
     * @return connectivity
     */
    Connectivity connectivity();

    /**
     * Creates CMP-27.
     *
     * @param channels CMP-26
     * @return the readiness checker
     */
    ReadinessChecker readinessChecker(NotificationChannels channels);

    /**
     * Creates CMP-28 settings intents.
     *
     * @param channels CMP-26
     * @return the settings intents
     */
    SystemSettingsIntents systemSettingsIntents(NotificationChannels channels);

    /**
     * Creates CMP-28 manufacturer detection.
     *
     * @return the detector
     */
    ManufacturerDetector manufacturerDetector();

    /**
     * Creates IF-08 (CE storage). Called off the main thread, on first use.
     *
     * @return the reading store
     */
    ReadingStore readings();

    /**
     * Creates IF-09 (CE storage). Called off the main thread, on first use.
     *
     * @return the entry store
     */
    EntryStore entries();

    /**
     * Creates IF-10 (CE storage). Called off the main thread, on first use.
     *
     * @return the device status store
     */
    DeviceStatusStore deviceStatus();

    /**
     * Creates IF-11 (CE storage). Called off the main thread, on first use.
     *
     * @return the settings store
     */
    SettingsStore settings();

    /**
     * Creates IF-12 / IF-61 (CE storage, Keystore). Called off the main thread, on first use.
     *
     * @return the connection store
     */
    ConnectionStore connection();

    /**
     * Creates IF-13 (CE storage). Called off the main thread, on first use.
     *
     * @return the monitor state store
     */
    MonitorStateStore monitorState();

    /**
     * Creates IF-14 (CE storage). Called off the main thread, on first use.
     *
     * @return the alarm state store
     */
    AlarmStateStore alarmState();

    /**
     * Creates IF-63 (CE storage). Called off the main thread, on first use.
     *
     * @return the reminder state store
     */
    ReminderStateStore reminderState();

    /**
     * Creates the one-time support prompt store (DCR-46, CE storage). Called off the main thread, on first use.
     *
     * @return the support prompt store
     */
    SupportPromptStore supportPrompt();

    /**
     * Creates the update check state store (DCR-49, CE storage). Called off the main thread, on first use.
     *
     * @return the update store
     */
    UpdateStore updateStore();

    /**
     * Creates the source of the newest stable version (DCR-49, the project's GitHub releases).
     *
     * @return the update source
     */
    UpdateSource updateSource();

    /**
     * Creates IF-64 (CE storage). Called off the main thread, on first use.
     *
     * @return the notified pump-alert store
     */
    NotifiedAlertStore notifiedAlerts();

    /**
     * Creates one IF-07 gateway instance (unconfigured). The container creates two: monitoring and probe (Q-46).
     *
     * @param connectivity IF-19
     * @return a new gateway
     */
    NightscoutGateway gateway(Connectivity connectivity);

    /**
     * Creates IF-17 (CP-09). M1: placeholder until CP-09 (see {@link M1Placeholders}).
     *
     * @return the reminder scheduler
     */
    ReminderScheduler reminderScheduler();

    /**
     * Creates IF-17b (CP-09). M1: placeholder until CP-09 (see {@link M1Placeholders}).
     *
     * @return the pump-alert notifier
     */
    PumpAlertNotifier pumpAlertNotifier();

    /**
     * Creates the {@code :monitor} runtime (CP-08) — once per process.
     *
     * @param collaborators its collaborators
     * @return the runtime and its IF-62
     */
    MonitorRuntimeHandle monitorRuntime(MonitorRuntime.Collaborators collaborators);

    /**
     * Creates the {@code :alarm} runtime (CP-07) — once per process.
     *
     * @param alarmActions    IF-25, resolved lazily (breaks the AlarmActions → IF-16 → AlarmRuntime cycle)
     * @param alarmStateStore IF-14
     * @param alarmMemory     the shared in-memory ENT-08
     * @param channels        CMP-26
     * @param monitorExecutor the single monitor thread
     * @return the runtime and its IF-16
     */
    AlarmRuntimeHandle alarmRuntime(Supplier<AlarmActions> alarmActions, AlarmStateStore alarmStateStore,
                                    AlarmStateMemory alarmMemory, NotificationChannels channels,
                                    Executor monitorExecutor);

    /**
     * Creates the F5 boot-count check (CP-08 {@code BootCountCheck}) for the APP_OPEN origin.
     *
     * @param flags IF-60
     * @return a task that runs the check once (call off the main thread)
     */
    Runnable bootCountCheck(AppFlagsStore flags);
}
