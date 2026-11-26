package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.sound.PlayableSound;
import app.lightscout.domain.sound.SoundKind;

/**
 * {@link SoundChoice} backed by the sound resolver (IF-69, DCR-51). The first call in a process may load the sound
 * preferences, so it runs on the player thread, never on main. A resolver that throws or answers with nothing means
 * the classic tone: an alarm is never silent because of the sound choice.
 */
final class ResolverSoundChoice implements SoundChoice {

    private final AlarmSoundResolver resolver;

    /**
     * @param resolver the read-only view of the chosen sounds
     */
    ResolverSoundChoice(AlarmSoundResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public SoundKind kind(AlarmType type) {
        try {
            PlayableSound sound = resolver.playable(type);
            return sound == null ? SoundKind.CLASSIC : sound.kind();
        } catch (RuntimeException e) {
            return SoundKind.CLASSIC;
        }
    }
}
