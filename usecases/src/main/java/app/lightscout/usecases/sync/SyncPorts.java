package app.lightscout.usecases.sync;

import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ConnectionStore;
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

import java.util.Objects;

/**
 * The ports used by the sync cycle, bundled for constructor injection by {@code AppContainer} (R-6, R-34).
 *
 * @param gateway           IF-07
 * @param connectionStore   IF-12 (base URL for the daily version check; the token is never read here)
 * @param readings          IF-08
 * @param entries           IF-09
 * @param deviceStatus      IF-10
 * @param settings          IF-11
 * @param monitorState      IF-13
 * @param alarmState        IF-14
 * @param reminderState     IF-63
 * @param notifiedAlerts    IF-64
 * @param alarmController   IF-16
 * @param reminderScheduler IF-17
 * @param pumpAlertNotifier IF-17b
 * @param clock             IF-15
 * @param logger            IF-66
 */
public record SyncPorts(NightscoutGateway gateway, ConnectionStore connectionStore,
                        ReadingStore readings, EntryStore entries, DeviceStatusStore deviceStatus,
                        SettingsStore settings, MonitorStateStore monitorState, AlarmStateStore alarmState,
                        ReminderStateStore reminderState, NotifiedAlertStore notifiedAlerts,
                        AlarmController alarmController, ReminderScheduler reminderScheduler,
                        PumpAlertNotifier pumpAlertNotifier, Clock clock, Logger logger) {

    /**
     * Creates the bundle.
     *
     * @throws NullPointerException if a port is null
     */
    public SyncPorts {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(connectionStore, "connectionStore");
        Objects.requireNonNull(readings, "readings");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(deviceStatus, "deviceStatus");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(monitorState, "monitorState");
        Objects.requireNonNull(alarmState, "alarmState");
        Objects.requireNonNull(reminderState, "reminderState");
        Objects.requireNonNull(notifiedAlerts, "notifiedAlerts");
        Objects.requireNonNull(alarmController, "alarmController");
        Objects.requireNonNull(reminderScheduler, "reminderScheduler");
        Objects.requireNonNull(pumpAlertNotifier, "pumpAlertNotifier");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(logger, "logger");
    }
}
