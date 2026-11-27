package app.lightscout.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

import java.util.List;
import org.junit.Test;

/** DCR-51 S7: which name a row shows, and which alarm types count as customised for the S-04 summary. */
public class SoundLabelsTest {

    private final SoundLibrary withSounds = SoundLibrary.EMPTY
            .addOwn(SoundFakes.own(SoundFakes.KEY_A, "Morning"))
            .addPhoneCopy(SoundFakes.phoneCopy(SoundFakes.KEY_B, "content://media/1", "Oxygen"));

    @Test
    public void alarmTypesAreListedInTheDesignOrder() {
        assertEquals(List.of(AlarmType.URGENT_LOW, AlarmType.LOW, AlarmType.NO_DATA, AlarmType.NOT_RUNNING,
                AlarmType.HIGH), SoundLabels.ORDER);
    }

    @Test
    public void classicHasNoCustomName() {
        assertNull(SoundLabels.customName(withSounds, SoundRef.CLASSIC));
    }

    @Test
    public void ownAndPhoneSoundsShowTheirStoredName() {
        assertEquals("Morning", SoundLabels.customName(withSounds, SoundRef.own(SoundFakes.KEY_A)));
        assertEquals("Oxygen", SoundLabels.customName(withSounds, SoundRef.phone(SoundFakes.KEY_B)));
    }

    @Test
    public void aReferenceThatIsNoLongerInTheLibraryShowsAsClassic() {
        assertNull(SoundLabels.customName(withSounds, SoundRef.own(SoundFakes.KEY_B)));
        assertNull(SoundLabels.customName(withSounds, SoundRef.phone(SoundFakes.KEY_A)));
        assertNull(SoundLabels.customName(withSounds, SoundRef.bundled("chime")));
    }

    @Test
    public void customisedTypesFollowTheDesignOrder() {
        // a phone copy is kept only once a type uses it, so it is selected first
        SoundLibrary lib = withSounds.select(AlarmType.LOW, SoundRef.phone(SoundFakes.KEY_B))
                .select(AlarmType.HIGH, SoundRef.own(SoundFakes.KEY_A));
        assertEquals(List.of(AlarmType.LOW, AlarmType.HIGH), SoundLabels.customisedTypes(lib));
    }

    @Test
    public void noCustomisedTypesWhenEverythingIsClassic() {
        assertEquals(List.of(), SoundLabels.customisedTypes(withSounds));
    }
}
