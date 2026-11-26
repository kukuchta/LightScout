package app.lightscout.domain.sound;

import java.util.List;

import org.junit.Test;

import app.lightscout.domain.alarm.AlarmType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** DCR-51: five independent choices, default LightScout classic for every alarm type. */
public class AlarmSoundChoiceTest {

    private static final SoundRef CHIME = SoundRef.bundled("chime");

    @Test
    public void everyTypeStartsOnClassic() {
        AlarmSoundChoice choice = AlarmSoundChoice.allClassic();
        for (AlarmType type : AlarmType.values()) {
            assertEquals(SoundRef.CLASSIC, choice.get(type));
        }
        assertTrue(choice.customisedTypes().isEmpty());
    }

    @Test
    public void eachTypeChangesOnItsOwn() {
        for (AlarmType changed : AlarmType.values()) {
            AlarmSoundChoice choice = AlarmSoundChoice.allClassic().with(changed, CHIME);
            for (AlarmType type : AlarmType.values()) {
                assertEquals(type == changed ? CHIME : SoundRef.CLASSIC, choice.get(type));
            }
        }
    }

    @Test
    public void withLeavesTheOriginalUntouched() {
        AlarmSoundChoice original = AlarmSoundChoice.allClassic();
        AlarmSoundChoice changed = original.with(AlarmType.LOW, CHIME);
        assertNotSame(original, changed);
        assertEquals(SoundRef.CLASSIC, original.get(AlarmType.LOW));
    }

    @Test
    public void settingTheSameValueReturnsTheSameObject() {
        AlarmSoundChoice choice = AlarmSoundChoice.allClassic();
        assertSame(choice, choice.with(AlarmType.HIGH, SoundRef.CLASSIC));
    }

    @Test
    public void customisedTypesFollowTheAlarmOrder() {
        AlarmSoundChoice choice = AlarmSoundChoice.allClassic().with(AlarmType.HIGH, CHIME)
                .with(AlarmType.LOW, CHIME);
        assertEquals(List.of(AlarmType.LOW, AlarmType.HIGH), choice.customisedTypes());
    }

    @Test
    public void typesUsingFindsEveryMatch() {
        AlarmSoundChoice choice = AlarmSoundChoice.allClassic().with(AlarmType.HIGH, CHIME)
                .with(AlarmType.NO_DATA, CHIME);
        assertEquals(List.of(AlarmType.NO_DATA, AlarmType.HIGH), choice.typesUsing(CHIME));
        assertEquals(5 - 2, choice.typesUsing(SoundRef.CLASSIC).size());
    }

    @Test
    public void equalChoicesAreEqual() {
        assertEquals(AlarmSoundChoice.allClassic().with(AlarmType.LOW, CHIME),
                AlarmSoundChoice.allClassic().with(AlarmType.LOW, CHIME));
        assertEquals(AlarmSoundChoice.allClassic().with(AlarmType.LOW, CHIME).hashCode(),
                AlarmSoundChoice.allClassic().with(AlarmType.LOW, CHIME).hashCode());
    }
}
