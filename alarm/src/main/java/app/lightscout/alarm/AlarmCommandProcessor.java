package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

import java.util.List;

/**
 * IF-16 command handling of {@code AlarmRuntime} (CP-07 §6 „Command handling”, CP-00A R-35, ADR-076), free of
 * Android types. {@link #apply} runs on {@code monitorExecutor} only.
 *
 * <p><b>Ordering (SS-02 assumption #2, Moderator):</b> the list is processed in order; it may hold
 * {@code Stop(X)} followed by {@code Start(Y)}. A {@code Stop} in the middle of the list is not the restore point:
 * the user's alarm volume is restored only when the whole {@code apply} ends with nothing ringing (R-35, F8).
 *
 * <p><b>Commands</b> (the runtime never stops ringing by itself — AL-4):
 * <ul>
 *   <li>{@code Start(type)}: idempotent (already ringing that type → {@code Update}); ringing another type →
 *       handled as {@code Replace}. A new alarm saves the user's volume with {@code commit()} before the first
 *       volume change (F8) and starts at escalation step 0; the first start of a process that finds a saved volume
 *       is the resume after process death (R-35) and continues at the persisted step.</li>
 *   <li>{@code Replace(from, to)}: keeps the escalation level (F8), cancels the replaced notification and posts the
 *       new one (F20); with nothing ringing it starts {@code to}.</li>
 *   <li>{@code Update(view)}: refreshes S-09 / CH-01, ensures the player plays, re-acquires the wake lock (F16);
 *       with nothing ringing it starts the type (keep-alive must ring, R-35).</li>
 *   <li>{@code Stop(type)}: only for the ringing type: stop sound and vibration, cancel CH-01, close S-09, release
 *       the wake lock.</li>
 * </ul>
 *
 * <p><b>Snooze request:</b> S-09 / the notification action silence the player at once ({@link #requestSnooze}). The
 * suppression of keep-alives of <b>that type only</b> until {@code Stop(type)} (or the 3-min fail-safe) lives in
 * {@link RingEngine} on the player thread, so check and restart are atomic (Q-71, IR-87). {@code Replace} /
 * {@code Start} of a different type rings at once (AL-6, F20); the later stale {@code Stop} of the snoozed type is
 * ignored because another type plays.
 */
final class AlarmCommandProcessor {

    private final AlarmStateAccess state;
    private final AlarmSound sound;
    private final AlarmSurface surface;
    private final Logger logger;

    private AlarmType ringing;
    private boolean startedInProcess;
    private Integer unsavedUserVolume;

    /**
     * Creates the processor.
     *
     * @param state   ENT-08 access (shared {@code AlarmStateMemory})
     * @param sound   player seam
     * @param surface notification / S-09 seam
     * @param logger  IF-66
     */
    AlarmCommandProcessor(AlarmStateAccess state, AlarmSound sound, AlarmSurface surface, Logger logger) {
        this.state = state;
        this.sound = sound;
        this.surface = surface;
        this.logger = logger;
    }

    /**
     * Applies one evaluation's commands in order, then the F8 restore point.
     *
     * @param commands the ordered commands, possibly empty
     */
    void apply(List<AlarmCommand> commands) {
        for (AlarmCommand c : commands) {
            if (c instanceof AlarmCommand.Start start) {
                onRing(start.type(), start.view());
            } else if (c instanceof AlarmCommand.Replace replace) {
                onRing(replace.toType(), replace.view());
            } else if (c instanceof AlarmCommand.Update update) {
                onRing(update.view().type(), update.view());
            } else if (c instanceof AlarmCommand.Stop stop) {
                onStop(stop.type());
            }
        }
        if (ringing == null) {
            restorePoint();
        }
    }

    /**
     * S-09 Snooze / notification action / volume key: silence the type at once (A-S09-1 „stops sound”;
     * {@link AlarmSound#silence} must not block). The caller also calls IF-25 {@code snooze(type)}. Any thread.
     *
     * @param type the snoozed type
     */
    void requestSnooze(AlarmType type) {
        sound.silence(type);
    }

    /**
     * Escalation progress from the player, posted onto {@code monitorExecutor} (R-35 read-modify-write).
     *
     * @param type the ringing type
     * @param step the new step
     */
    void onStep(AlarmType type, int step) {
        if (ringing != type) {
            return;
        }
        AlarmState s = state.read();
        if (AlarmStateEdits.step(s, type) != step) {
            state.write(AlarmStateEdits.withStep(s, type, step));
        }
    }

    /**
     * Returns the type ringing in this process.
     *
     * @return the type, or {@code null}
     */
    AlarmType ringing() {
        return ringing;
    }

    /** Start / Replace / Update all end with {@code type} ringing and showing {@code view}. */
    private void onRing(AlarmType type, AlarmView view) {
        if (ringing == null) {
            startNew(type, view);
        } else if (ringing == type) {
            keepAlive(type, view);
        } else {
            replace(ringing, type, view);
        }
    }

    private void startNew(AlarmType type, AlarmView view) {
        AlarmState s = state.read();
        Integer saved = s.savedUserAlarmVolume() != null ? s.savedUserAlarmVolume() : unsavedUserVolume;
        boolean resume = !startedInProcess && s.savedUserAlarmVolume() != null;
        int step;
        if (saved == null) {
            int user = sound.currentVolume();
            AlarmState next = AlarmStateEdits.withStep(AlarmStateEdits.withSavedVolume(s, user), type, 0);
            // F8: committed BEFORE the first volume change; a failed write still rings (WF-02 step 1 „ring anyway”)
            unsavedUserVolume = state.write(next) ? null : user;
            saved = user;
            step = 0;
        } else if (resume) {
            step = AlarmStateEdits.step(s, type);
        } else {
            step = 0;
            if (AlarmStateEdits.step(s, type) != 0) {
                state.write(AlarmStateEdits.withStep(s, type, 0));
            }
        }
        startedInProcess = true;
        ringing = type;
        safely(() -> surface.show(view));
        sound.start(type, step, saved);
        logger.event(EventCode.ALARM_RING, type.name());
    }

    private void keepAlive(AlarmType type, AlarmView view) {
        safely(() -> surface.update(view));
        AlarmState s = state.read();
        sound.keepAlive(type, AlarmStateEdits.step(s, type), baseVolume(s));   // engine ignores a snoozed type
    }

    private void replace(AlarmType from, AlarmType to, AlarmView view) {
        ringing = to;
        safely(() -> surface.replace(view));
        AlarmState s = state.read();
        sound.keepAlive(to, sound.currentStep(), baseVolume(s));   // restarts only if silenced by a snooze request
        sound.switchType(to);
        logger.event(EventCode.ALARM_REPLACE, from.name() + ">" + to.name());
    }

    private void onStop(AlarmType type) {
        if (ringing != type) {
            return;
        }
        ringing = null;
        sound.stop();
        safely(surface::close);
        logger.event(EventCode.ALARM_STOP, type.name());
    }

    /** F8 / R-35: nothing rings at the end of {@code apply} → restore the saved user volume and clear it. */
    private void restorePoint() {
        AlarmState s = state.read();
        Integer saved = savedVolume(s);
        if (saved == null) {
            return;
        }
        sound.restoreVolume(saved);
        unsavedUserVolume = null;
        if (s.savedUserAlarmVolume() != null) {
            state.write(AlarmStateEdits.withSavedVolume(s, null));
        }
    }

    private Integer savedVolume(AlarmState s) {
        return s.savedUserAlarmVolume() != null ? s.savedUserAlarmVolume() : unsavedUserVolume;
    }

    /**
     * Base of the D-023 schedule while ringing: the saved user volume; IR-86: if it is unexpectedly missing (e.g.
     * another writer replaced ENT-08), the current stream volume is used — never a null unboxing on
     * {@code monitorExecutor}.
     */
    private int baseVolume(AlarmState s) {
        Integer saved = savedVolume(s);
        return saved != null ? saved : sound.currentVolume();
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // WF-02 step 2 „P”: a notification / screen failure never prevents the sound (SR-37)
        }
    }
}
