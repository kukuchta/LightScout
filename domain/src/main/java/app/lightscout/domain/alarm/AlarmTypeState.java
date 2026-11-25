package app.lightscout.domain.alarm;

import java.time.Instant;

/**
 * Persisted state of one alarm type (ENT-08, CP-00A R-4).
 *
 * @param ringingSince       when the type started ringing; nullable
 * @param snoozedUntil       end of the snooze of this type on this phone; nullable
 * @param escalationStep     volume escalation step of the player
 * @param triggerReadingTime time of the reading that triggered it; nullable
 */
public record AlarmTypeState(Instant ringingSince, Instant snoozedUntil,
                             int escalationStep, Instant triggerReadingTime) {
}
