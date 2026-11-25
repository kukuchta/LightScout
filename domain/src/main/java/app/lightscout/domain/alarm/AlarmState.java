package app.lightscout.domain.alarm;

import java.util.Map;
import java.util.Objects;

/**
 * Per-phone alarm state (ENT-08, CP-00A R-4). Never shared between phones (CON-07).
 * A type missing from {@code perType} has no ringing, no snooze and escalation step 0.
 *
 * @param activeType           the one type ringing now; nullable
 * @param perType              unmodifiable state per type
 * @param savedUserAlarmVolume user alarm volume saved before the first volume change (F8); nullable
 */
public record AlarmState(AlarmType activeType,
                         Map<AlarmType, AlarmTypeState> perType,
                         Integer savedUserAlarmVolume) {

    /**
     * Creates an alarm state; the map is copied unmodifiably.
     *
     * @throws NullPointerException if the map, a key or a value is null
     */
    public AlarmState {
        perType = Map.copyOf(Objects.requireNonNull(perType, "perType"));
    }

    /**
     * Returns the state with nothing ringing, nothing snoozed and no saved volume.
     *
     * @return the empty state
     */
    public static AlarmState empty() {
        return new AlarmState(null, Map.of(), null);
    }

    /**
     * Returns the state kept by the server-change reset (R-71): the ringing type and its per-type state stay
     * (a ringing alarm is ended only by Snooze — AL-4); every other type's state, including snoozes, is dropped.
     * {@code savedUserAlarmVolume} is kept so that the alarm runtime can still restore the user volume (F8).
     *
     * @return the reduced state
     */
    public AlarmState keepRingingOnly() {
        if (activeType == null) {
            return new AlarmState(null, Map.of(), savedUserAlarmVolume);
        }
        AlarmTypeState ringing = perType.get(activeType);
        Map<AlarmType, AlarmTypeState> kept = ringing == null ? Map.of() : Map.of(activeType, ringing);
        return new AlarmState(activeType, kept, savedUserAlarmVolume);
    }
}
