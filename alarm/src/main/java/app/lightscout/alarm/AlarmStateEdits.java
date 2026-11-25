package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;

import java.util.EnumMap;
import java.util.Map;

/**
 * The {@code :alarm} share of ENT-08 writes (CP-00A R-35): {@code savedUserAlarmVolume} and
 * {@code perType.escalationStep}. All other fields are copied unchanged.
 */
final class AlarmStateEdits {

    private AlarmStateEdits() {
    }

    /**
     * Returns the state with another saved user volume.
     *
     * @param s      state
     * @param volume saved volume, {@code null} to clear
     * @return the new state
     */
    static AlarmState withSavedVolume(AlarmState s, Integer volume) {
        return new AlarmState(s.activeType(), s.perType(), volume);
    }

    /**
     * Returns the state with another escalation step for a type; an entry that becomes all-empty is dropped.
     *
     * @param s    state
     * @param type the type
     * @param step the step
     * @return the new state
     */
    static AlarmState withStep(AlarmState s, AlarmType type, int step) {
        Map<AlarmType, AlarmTypeState> perType = new EnumMap<>(AlarmType.class);
        perType.putAll(s.perType());
        AlarmTypeState prev = perType.get(type);
        AlarmTypeState next = prev == null
                ? new AlarmTypeState(null, null, step, null)
                : new AlarmTypeState(prev.ringingSince(), prev.snoozedUntil(), step, prev.triggerReadingTime());
        if (next.ringingSince() == null && next.snoozedUntil() == null && next.escalationStep() == 0
                && next.triggerReadingTime() == null) {
            perType.remove(type);
        } else {
            perType.put(type, next);
        }
        return new AlarmState(s.activeType(), perType, s.savedUserAlarmVolume());
    }

    /**
     * Returns the persisted escalation step of a type.
     *
     * @param s    state
     * @param type the type
     * @return the step, 0 when none
     */
    static int step(AlarmState s, AlarmType type) {
        AlarmTypeState t = s.perType().get(type);
        return t == null ? 0 : t.escalationStep();
    }
}
