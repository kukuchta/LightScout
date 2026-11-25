package app.lightscout.usecases.connection;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.ReminderScheduler;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.common.ServerEpoch;

import java.util.Objects;

/**
 * The two {@code monitorExecutor} tasks of IF-24 {@code save} (DS:A-23, WF-05, R-22, R-32, R-71). They run on the
 * serial monitor thread between ticks, so no tick interleaves with them („stop ticks → clear → restart”); both first
 * configure the <b>monitoring</b> gateway with the saved connection (JWT discarded, SR-03; Q-46: only here, never
 * from {@code verify}), and both end with {@code requestTick(RECONFIGURED)}, which restarts with SY-8 after a
 * server change.
 *
 * <p>The composition root passes the same {@link AlarmStateMemory} and {@link MonitorStateMemory} instances that the
 * sync cycle, the projector and IF-25 use (SS-05 IR-38), the {@link OriginBoundTokenSource} the monitoring gateway
 * is configured with (IR-45) and the {@link ServerEpoch} shared with the HistoryLoader and the EntryWriter (IR-49).
 */
public final class ConnectionStateReset {

    private final NightscoutGateway monitoringGateway;
    private final OriginBoundTokenSource tokens;
    private final ServerEpoch epoch;
    private final ReadingStore readings;
    private final EntryStore entries;
    private final DeviceStatusStore deviceStatus;
    private final MonitorStateStore monitorState;
    private final AlarmStateStore alarmState;
    private final ReminderStateStore reminderState;
    private final NotifiedAlertStore notifiedAlerts;
    private final ReminderScheduler reminderScheduler;
    private final AlarmStateMemory alarmMemory;
    private final MonitorStateMemory monitorMemory;
    private final MonitorControl monitorControl;

    /**
     * Creates the reset tasks.
     *
     * @param monitoringGateway IF-07 instance used by the sync cycle (never the verification probe, Q-46)
     * @param tokens            origin-bound token source of the monitoring gateway (IR-45)
     * @param epoch             server epoch shared with the io-thread cache writers (IR-49)
     * @param readings          IF-08
     * @param entries           IF-09
     * @param deviceStatus      IF-10
     * @param monitorState      IF-13
     * @param alarmState        IF-14
     * @param reminderState     IF-63
     * @param notifiedAlerts    IF-64
     * @param reminderScheduler IF-17 (cancel the old server's reminders)
     * @param alarmMemory       in-memory ENT-08 shared with the cycle, projector and IF-25
     * @param monitorMemory     in-memory ENT-07 shared with the cycle and projector
     * @param monitorControl    IF-62
     */
    public ConnectionStateReset(NightscoutGateway monitoringGateway, OriginBoundTokenSource tokens, ServerEpoch epoch,
                                ReadingStore readings, EntryStore entries, DeviceStatusStore deviceStatus,
                                MonitorStateStore monitorState, AlarmStateStore alarmState,
                                ReminderStateStore reminderState, NotifiedAlertStore notifiedAlerts,
                                ReminderScheduler reminderScheduler, AlarmStateMemory alarmMemory,
                                MonitorStateMemory monitorMemory, MonitorControl monitorControl) {
        this.monitoringGateway = Objects.requireNonNull(monitoringGateway, "monitoringGateway");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        this.readings = Objects.requireNonNull(readings, "readings");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.deviceStatus = Objects.requireNonNull(deviceStatus, "deviceStatus");
        this.monitorState = Objects.requireNonNull(monitorState, "monitorState");
        this.alarmState = Objects.requireNonNull(alarmState, "alarmState");
        this.reminderState = Objects.requireNonNull(reminderState, "reminderState");
        this.notifiedAlerts = Objects.requireNonNull(notifiedAlerts, "notifiedAlerts");
        this.reminderScheduler = Objects.requireNonNull(reminderScheduler, "reminderScheduler");
        this.alarmMemory = Objects.requireNonNull(alarmMemory, "alarmMemory");
        this.monitorMemory = Objects.requireNonNull(monitorMemory, "monitorMemory");
        this.monitorControl = Objects.requireNonNull(monitorControl, "monitorControl");
    }

    /**
     * Server change (R-71; monitor thread only): configure the monitoring gateway with the new server, then
     * clear ST-01 (IF-08/09/10) and ENT-07…ENT-10 (IF-13, IF-63, IF-64);
     * keep only a ringing alarm (a ringing alarm ends only by Snooze — AL-4); forget the per-server memories; cancel
     * the reminders; then {@code requestTick(RECONFIGURED)}. ENT-11 flags are never touched (R-22). Each step is
     * independent: a store failure (logged by the store) never stops the others or the restart (SR-37).
     *
     * @param baseUrl the saved (normalised) base URL
     */
    void resetServer(String baseUrl) {
        bindAndConfigure(baseUrl);
        epoch.advance(() -> {   // IR-49: io-thread writes of operations started before this point are dropped
            clearQuietly(readings::clear);
            clearQuietly(entries::clear);
            clearQuietly(deviceStatus::clear);
        });
        clearQuietly(monitorState::clear);
        clearQuietly(reminderState::clear);
        clearQuietly(notifiedAlerts::clear);
        AlarmState current = alarmMemory.read(alarmState);
        if (alarmMemory.known()) {
            // never write the empty() fallback over an unreadable store (it would end a ringing alarm's state)
            clearQuietly(() -> alarmState.put(current.keepRingingOnly()));
        }
        alarmMemory.reset();
        monitorMemory.reset();
        for (ConsumableKind k : ConsumableKind.values()) {
            try {
                reminderScheduler.cancel(k);
            } catch (RuntimeException e) {
                // notifications are best effort; the restart must happen (SR-37)
            }
        }
        monitorControl.requestTick(TickReason.RECONFIGURED);
    }

    /**
     * Token-only change (same server, R-71; monitor thread only): no clearing; the sticky {@code connectionProblem}
     * is cleared (R-67: an S-06 save clears it); then {@code requestTick(RECONFIGURED)}. The monitoring gateway is
     * configured again first, so the JWT of the old token is discarded (SR-03).
     *
     * @param baseUrl the saved (normalised) base URL
     */
    void clearConnectionProblem(String baseUrl) {
        bindAndConfigure(baseUrl);
        try {
            MonitorState ms = monitorState.get();
            if (ms.connectionProblem() != null) {
                ms = new MonitorState(ms.monitoringSince(), ms.lastSuccessfulCheck(), ms.lastCheckCompleted(),
                        ms.lastOutcome(), null, ms.lastReadingTime(), ms.arrivalLagMedianSec(), ms.recentLagSec(),
                        ms.lastDailyMaintenance(), ms.cursors());
                monitorState.put(ms);
            }
            monitorMemory.set(ms);
        } catch (StorageException e) {
            // logged by the store (R-13); the next daily check settles connectionProblem
        }
        monitorControl.requestTick(TickReason.RECONFIGURED);
    }

    /**
     * IR-53 (SR-13 / C-13): unbind, configure, then bind. While the gateway may still point at the old URL the source
     * is bound to nothing, so a JWT refresh on another thread in between never gets the new token for the old origin.
     */
    private void bindAndConfigure(String baseUrl) {
        tokens.bind(null);
        monitoringGateway.configure(baseUrl, tokens);
        tokens.bind(baseUrl);
    }

    private static void clearQuietly(Runnable step) {
        try {
            step.run();
        } catch (StorageException e) {
            // logged by the store (R-13)
        }
    }
}
