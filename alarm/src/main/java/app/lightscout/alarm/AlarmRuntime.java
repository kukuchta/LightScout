package app.lightscout.alarm;

import android.content.Context;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmController;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Logger;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.alarm.AlarmStateMemory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * CMP-40 AlarmRuntime — implements IF-16 {@link AlarmController} (CP-07 §4.1, §6; CP-00A R-35, R-36, R-80). One per
 * process, created lazily by the CP-15 composition root after the first unlock and handed to {@code SyncCycle} /
 * {@code AlarmActions} as IF-16 and to the {@code :alarm} components through {@link AlarmRuntimeProvider}.
 *
 * <p>{@link #apply} is called on {@code monitorExecutor} inside the running {@code MonitorService} (F3); the player
 * runs on its own thread and notifications are posted on the main looper. Command semantics:
 * {@link AlarmCommandProcessor}. This class never decides alarm rules (CP-01) and never starts
 * {@code MonitorService}.
 */
public final class AlarmRuntime implements AlarmController {

    private final Context context;
    private final Supplier<AlarmActions> alarmActions;
    private final Clock clock;
    private final AlarmScreenModel screen = new AlarmScreenModel();
    private final AlarmSurfaceController surface;
    private final AlarmCommandProcessor processor;

    /**
     * Creates the runtime. No disk or network work happens here (safe on the main thread).
     *
     * @param context         any context (the application context is kept)
     * @param alarmActions    IF-25, supplied lazily ({@code AlarmActions} itself needs IF-16 — breaks the cycle)
     * @param alarmStateStore IF-14 (ST-04, {@code commit()})
     * @param alarmMemory     the in-memory ENT-08 shared with {@code SyncCycle} and {@code AlarmActions} (R-35)
     * @param channels        CMP-26 (current CH-01 id per post — F7, Q-52)
     * @param clock           IF-15
     * @param logger          IF-66 (event codes only, SR-07)
     * @param monitorExecutor the single monitor thread (escalation-step persistence, R-34 / R-35)
     * @param soundResolver   IF-69, the sound the user chose per alarm type (DCR-51); it may load storage on its
     *                        first call, so the player calls it on its own thread only
     */
    public AlarmRuntime(Context context, Supplier<AlarmActions> alarmActions, AlarmStateStore alarmStateStore,
                        AlarmStateMemory alarmMemory, NotificationChannels channels, Clock clock, Logger logger,
                        Executor monitorExecutor, AlarmSoundResolver soundResolver) {
        this.context = context.getApplicationContext();
        this.alarmActions = Objects.requireNonNull(alarmActions, "alarmActions");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(monitorExecutor, "monitorExecutor");
        this.surface = new AlarmSurfaceController(new AndroidNotificationPort(this.context, clock), channels, screen,
                new AlarmScreenOffReceiver(this.context));
        AlarmCommandProcessor[] self = new AlarmCommandProcessor[1];
        AlarmPlayer player = new AlarmPlayer(this.context, logger,
                (type, step) -> monitorExecutor.execute(() -> self[0].onStep(type, step)),
                surface::onCallEnded, clock::elapsedRealtimeMs,
                Objects.requireNonNull(soundResolver, "soundResolver"));
        this.processor = new AlarmCommandProcessor(new AlarmStateAccess(alarmMemory, alarmStateStore), player,
                surface, logger);
        self[0] = processor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Must be called on {@code monitorExecutor}, once per evaluation, possibly with an empty list (the F8 restore
     * point). The list is processed in order.
     */
    @Override
    public void apply(List<AlarmCommand> commands) {
        processor.apply(commands);
    }

    /**
     * Snooze from S-09 (button, volume key) or the notification action (A-S09-1, A-S11-1): silences the type at once
     * and calls IF-25 {@code snooze(type)}, which persists {@code snoozedUntil} and applies {@code Stop(type)} on
     * {@code monitorExecutor}. Non-blocking; any thread.
     *
     * @param type the snoozed type
     */
    public void requestSnooze(AlarmType type) {
        processor.requestSnooze(type);
        alarmActions.get().snooze(type);
    }

    /**
     * Returns the S-09 content model.
     *
     * @return the model of this process
     */
    public AlarmScreenModel screen() {
        return screen;
    }

    /**
     * Returns the clock used by S-09.
     *
     * @return IF-15
     */
    public Clock clock() {
        return clock;
    }

    /**
     * F9: the CH-01 alarm notification was swiped away → re-post (only-alert-once) when that type still rings in
     * this process. Never a snooze (AL-4).
     *
     * @param type type named by the delete intent
     */
    void onNotificationDeleted(AlarmType type) {
        surface.onNotificationDeleted(type);
    }

    /**
     * Returns the application context.
     *
     * @return context
     */
    Context context() {
        return context;
    }
}
