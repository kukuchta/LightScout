package app.lightscout.usecases.alarm;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.Settings;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.SyncCycle;
import app.lightscout.usecases.common.SettingsEditor;
import app.lightscout.usecases.common.SettingsWriter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * CMP-15 implementation of IF-25 (CP-02 §8.7; 06 AL-5, F20, §5.2; CP-00A R-29, R-35). Every method only posts a
 * task to {@code monitorExecutor}, the single writer of alarm state and settings switches (03 §6.4); snooze,
 * switches and the alarm state are never synced between phones (CON-07).
 */
public final class DefaultAlarmActions implements AlarmActions {

    private final Executor monitorExecutor;
    private final SettingsWriter settings;
    private final AlarmStateStore alarmState;
    private final AlarmStateMemory alarmMemory;
    private final AlarmController alarmController;
    private final SyncCycle syncCycle;
    private final MonitorControl monitorControl;
    private final Clock clock;
    private final Logger logger;

    /**
     * Creates the use case.
     *
     * @param monitorExecutor the single monitor thread
     * @param settings        the shared ST-02 writer (IF-11), also used by IF-26 (SS-06 IR-44)
     * @param alarmState      IF-14
     * @param alarmMemory     in-memory ENT-08 shared with the sync cycle
     * @param alarmController IF-16
     * @param syncCycle       IF-20 (re-evaluation after a snooze)
     * @param monitorControl  IF-62 (re-evaluation after a switch change — ASR-17)
     * @param clock           IF-15
     * @param logger          IF-66
     */
    public DefaultAlarmActions(Executor monitorExecutor, SettingsWriter settings, AlarmStateStore alarmState,
                               AlarmStateMemory alarmMemory, AlarmController alarmController, SyncCycle syncCycle, MonitorControl monitorControl,
                               Clock clock, Logger logger) {
        this.monitorExecutor = Objects.requireNonNull(monitorExecutor, "monitorExecutor");
        this.settings = settings;
        this.alarmState = alarmState;
        this.alarmMemory = alarmMemory;
        this.alarmController = alarmController;
        this.syncCycle = syncCycle;
        this.monitorControl = monitorControl;
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * {@inheritDoc}
     *
     * <p>On the monitor thread: {@code snoozedUntil = now + settings.snoozeMinutes(t)}, ringing cleared, then
     * {@code Stop(t)} via IF-16, then {@code SyncCycle.reevaluate()} so that a pending lower type rings right away
     * if it holds and is not snoozed (F20, AL-6).
     */
    @Override
    public void snooze(AlarmType t) {
        Objects.requireNonNull(t, "type");
        monitorExecutor.execute(() -> doSnooze(t));
    }

    private void doSnooze(AlarmType t) {
        Instant now = clock.now();
        Settings s;
        try {
            s = settings.read();
        } catch (StorageException e) {
            s = Settings.defaults();
        }
        // A failed IF-14 put keeps the snoozed state in memory (WF-02 step 5), so the re-evaluation below still
        // runs the AL-6 / F20 pending re-check and the snoozed type does not ring again.
        // IR-36: with no known state the snooze stays pending in memory (never written over the store).
        alarmMemory.snooze(alarmState, t, now.plusSeconds(60L * s.snoozeMinutes(t)));
        try {
            alarmController.apply(List.of(new AlarmCommand.Stop(t)));
        } catch (RuntimeException e) {
            // the runtime failure must not block the re-evaluation (SR-37)
        }
        logger.event(EventCode.ALARM_SNOOZE, t.name());
        syncCycle.reevaluate();
    }

    @Override
    public void setAlarmsEnabled(boolean on) {
        monitorExecutor.execute(() -> updateSettings(s -> SettingsEditor.withAlarmsEnabled(s, on)));
    }

    @Override
    public void setAlarmTypeEnabled(AlarmType t, boolean on) {
        if (t == AlarmType.LOW) {
            monitorExecutor.execute(() -> updateSettings(s -> SettingsEditor.withLowEnabled(s, on)));
        } else if (t == AlarmType.HIGH) {
            monitorExecutor.execute(() -> updateSettings(s -> SettingsEditor.withHighEnabled(s, on)));
        }
        // URGENT_LOW is always on; NO_DATA / NOT_RUNNING have no switch → no-op (R-29, DS:D-021)
    }

    /** Writes ST-02, then IF-62 {@code reevaluateAlarms()} (ASR-17; switch-off while ringing → R-60 in IF-01). */
    private void updateSettings(java.util.function.UnaryOperator<Settings> change) {
        try {
            settings.apply(change);   // get and put under the shared writer lock (IR-44)
        } catch (StorageException e) {
            return;
        }
        monitorControl.reevaluateAlarms();
    }
}
