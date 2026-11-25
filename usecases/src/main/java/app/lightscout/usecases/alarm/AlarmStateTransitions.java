package app.lightscout.usecases.alarm;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Applies IF-01 commands and snoozes to the persisted {@link AlarmState} (AL-9, WF-02 step 1; CP-00A R-29, R-35).
 *
 * <p>Fields written here are the {@code :usecases} share of ENT-08: {@code activeType}, {@code ringingSince},
 * {@code snoozedUntil}, {@code triggerReadingTime}. {@code escalationStep} and {@code savedUserAlarmVolume} belong
 * to {@code :alarm} and are copied unchanged. Commands are applied in list order (Stop then Start in one decision
 * is valid), and the new state is persisted before {@code AlarmController.apply} is called.
 */
public final class AlarmStateTransitions {

    private AlarmStateTransitions() {
    }

    /**
     * Applies the ordered command list of one decision.
     *
     * @param state    current state
     * @param commands commands in order
     * @param now      evaluation instant
     * @return the new state (equal to {@code state} when nothing changed)
     */
    public static AlarmState afterCommands(AlarmState state, List<AlarmCommand> commands, Instant now) {
        AlarmType active = state.activeType();
        Map<AlarmType, AlarmTypeState> perType = copy(state.perType());
        for (AlarmCommand c : commands) {
            if (c instanceof AlarmCommand.Stop stop) {
                perType.put(stop.type(), notRinging(perType.get(stop.type())));
                if (active == stop.type()) {
                    active = null;
                }
            } else if (c instanceof AlarmCommand.Start start) {
                AlarmTypeState prev = perType.get(start.type());
                boolean alreadyRinging = active == start.type() && prev != null && prev.ringingSince() != null;
                perType.put(start.type(), ringing(prev, alreadyRinging ? prev.ringingSince() : now,
                        start.view().readingTime()));
                active = start.type();
            } else if (c instanceof AlarmCommand.Replace replace) {
                perType.put(replace.fromType(), notRinging(perType.get(replace.fromType())));
                perType.put(replace.toType(), ringing(perType.get(replace.toType()), now,
                        replace.view().readingTime()));
                active = replace.toType();
            } else if (c instanceof AlarmCommand.ClearSnooze clear) {
                AlarmTypeState prev = perType.get(clear.type());
                if (prev != null) {
                    perType.put(clear.type(), new AlarmTypeState(prev.ringingSince(), null,
                            prev.escalationStep(), prev.triggerReadingTime()));
                }
            }
            // Update: keep-alive only, no state change (R-35)
        }
        return new AlarmState(active, prune(perType), state.savedUserAlarmVolume());
    }

    /**
     * Applies a snooze: {@code snoozedUntil = until}, the type stops ringing and {@code activeType} is cleared
     * when it is that type (R-29, AL-5).
     *
     * @param state current state
     * @param type  snoozed type
     * @param until end of the snooze
     * @return the new state
     */
    public static AlarmState afterSnooze(AlarmState state, AlarmType type, Instant until) {
        Map<AlarmType, AlarmTypeState> perType = copy(state.perType());
        AlarmTypeState prev = perType.get(type);
        int step = prev == null ? 0 : prev.escalationStep();
        perType.put(type, new AlarmTypeState(null, until, step, null));
        AlarmType active = state.activeType() == type ? null : state.activeType();
        return new AlarmState(active, prune(perType), state.savedUserAlarmVolume());
    }

    private static AlarmTypeState ringing(AlarmTypeState prev, Instant since, Instant triggerReadingTime) {
        Instant snoozedUntil = prev == null ? null : prev.snoozedUntil();
        int step = prev == null ? 0 : prev.escalationStep();
        return new AlarmTypeState(since, snoozedUntil, step, triggerReadingTime);
    }

    private static AlarmTypeState notRinging(AlarmTypeState prev) {
        if (prev == null) {
            return null;
        }
        return new AlarmTypeState(null, prev.snoozedUntil(), prev.escalationStep(), null);
    }

    private static Map<AlarmType, AlarmTypeState> copy(Map<AlarmType, AlarmTypeState> perType) {
        Map<AlarmType, AlarmTypeState> m = new EnumMap<>(AlarmType.class);
        m.putAll(perType);
        return m;
    }

    /** Drops null and all-empty entries so that equal states compare equal. */
    private static Map<AlarmType, AlarmTypeState> prune(Map<AlarmType, AlarmTypeState> perType) {
        Map<AlarmType, AlarmTypeState> m = new EnumMap<>(AlarmType.class);
        for (Map.Entry<AlarmType, AlarmTypeState> e : perType.entrySet()) {
            AlarmTypeState s = e.getValue();
            if (s != null && (s.ringingSince() != null || s.snoozedUntil() != null || s.escalationStep() != 0
                    || s.triggerReadingTime() != null)) {
                m.put(e.getKey(), s);
            }
        }
        return m;
    }
}
