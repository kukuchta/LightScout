package app.lightscout.alarm;

import org.junit.Test;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.sound.PlayableSound;
import app.lightscout.domain.sound.SoundKind;

import static org.junit.Assert.assertEquals;

/**
 * DCR-51 S4: the engine asks the resolver which kind of sound a type has. A resolver that throws or answers with
 * nothing gives the classic tone: an alarm is never silent because of the sound choice.
 */
public class ResolverSoundChoiceTest {

    private static AlarmSoundResolver resolverReturning(PlayableSound sound) {
        return new AlarmSoundResolver() {
            @Override
            public PlayableSound playable(AlarmType type) {
                return sound;
            }

            @Override
            public void reportUnplayable(AlarmType type) {
                // not needed here
            }
        };
    }

    @Test
    public void theKindOfThePlayableSoundIsReported() {
        assertEquals(SoundKind.CLASSIC,
                new ResolverSoundChoice(resolverReturning(PlayableSound.CLASSIC)).kind(AlarmType.LOW));
        assertEquals(SoundKind.BUNDLED,
                new ResolverSoundChoice(resolverReturning(PlayableSound.bundled("chime"))).kind(AlarmType.LOW));
        assertEquals(SoundKind.OWN,
                new ResolverSoundChoice(resolverReturning(PlayableSound.file("/data/x/own-a", 10))).kind(AlarmType.LOW));
    }

    @Test
    public void aResolverThatThrowsGivesTheClassicTone() {
        AlarmSoundResolver throwing = new AlarmSoundResolver() {
            @Override
            public PlayableSound playable(AlarmType type) {
                throw new IllegalStateException("disk gone");
            }

            @Override
            public void reportUnplayable(AlarmType type) {
                // not needed here
            }
        };
        assertEquals(SoundKind.CLASSIC, new ResolverSoundChoice(throwing).kind(AlarmType.URGENT_LOW));
    }

    @Test
    public void aResolverThatAnswersNothingGivesTheClassicTone() {
        assertEquals(SoundKind.CLASSIC, new ResolverSoundChoice(resolverReturning(null)).kind(AlarmType.HIGH));
    }
}
