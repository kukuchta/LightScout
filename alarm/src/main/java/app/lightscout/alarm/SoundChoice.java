package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.SoundKind;

/**
 * Which kind of sound the user chose for an alarm type (DCR-51), as the {@link RingEngine} needs it: only to decide
 * whether the chain starts with {@link SoundStage#SELECTED_SOUND}, and for the {@code ALARM_SOUND} log detail. Free of
 * Android types and of any file access; the player supplies it from the sound resolver.
 */
interface SoundChoice {

    /**
     * Returns the kind of sound of an alarm type. Never throws.
     *
     * @param type alarm type
     * @return {@link SoundKind#CLASSIC} when nothing is chosen or anything is wrong
     */
    SoundKind kind(AlarmType type);
}
