package app.lightscout.domain.port;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.PlayableSound;

/**
 * Read-only view for the alarm player (IF-69, DCR-51), implemented by {@code :storage} next to {@link
 * AlarmSoundStore}. It must never keep an alarm silent: every problem answers with the classic tone.
 */
public interface AlarmSoundResolver {

    /**
     * Returns what to play for an alarm type. Never throws and never blocks after the first load.
     *
     * @param type alarm type
     * @return the sound to open; {@link PlayableSound#CLASSIC} when nothing is chosen or anything is wrong
     */
    PlayableSound playable(AlarmType type);

    /**
     * Records that the chosen sound could not be played, so the settings screen can say so. Never blocks.
     *
     * @param type alarm type whose sound failed
     */
    void reportUnplayable(AlarmType type);
}
