package app.lightscout.storage;

import java.util.List;

import org.junit.Test;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.AlarmSoundChoice;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** DCR-51: the library is one JSON preference value; damaged or unknown content never throws and never blocks an alarm. */
public class SoundLibraryJsonTest {

    private static final String KEY_A = "00000000000000a1";
    private static final String KEY_B = "00000000000000b2";
    private static final String KEY_C = "00000000000000c3";

    private static final OwnSound OWN_A = new OwnSound(KEY_A, "Morning beep", "own-" + KEY_A, 5000L);
    private static final OwnSound OWN_B = new OwnSound(KEY_B, "Siren", "own-" + KEY_B, 7000L);
    private static final PhoneCopy PHONE_C = new PhoneCopy(KEY_C, "content://media/internal/audio/media/6",
            "Beep", "phone-" + KEY_C, 13_626L);

    private static SoundLibrary full() {
        return SoundLibrary.EMPTY.addOwn(OWN_A).addOwn(OWN_B).addPhoneCopy(PHONE_C)
                .select(AlarmType.LOW, SoundRef.phone(KEY_C))
                .select(AlarmType.HIGH, SoundRef.own(KEY_A))
                .remove(KEY_B);
    }

    @Test
    public void aFullLibraryRoundTrips() {
        SoundLibrary library = full();
        SoundLibrary back = SoundLibraryJson.read(SoundLibraryJson.write(library), library.choice());
        assertEquals(library, back);
    }

    @Test
    public void anEmptyLibraryRoundTrips() {
        SoundLibrary back = SoundLibraryJson.read(SoundLibraryJson.write(SoundLibrary.EMPTY),
                AlarmSoundChoice.allClassic());
        assertEquals(SoundLibrary.EMPTY, back);
    }

    @Test
    public void textThatIsNotJsonReadsAsNothingStoredButKeepsBundledChoices() {
        AlarmSoundChoice choice = AlarmSoundChoice.allClassic()
                .with(AlarmType.LOW, SoundRef.bundled("chime"))
                .with(AlarmType.HIGH, SoundRef.own(KEY_A));
        for (String text : new String[] {null, "", "{", "[]", "not json", "{\"own\":5}", "{\"own\":[1,2]}"}) {
            SoundLibrary back = SoundLibraryJson.read(text, choice);
            assertTrue("no own sounds from: " + text, back.ownSounds().isEmpty());
            assertEquals(SoundRef.bundled("chime"), back.choice().get(AlarmType.LOW));
            assertEquals("missing own sound falls back", SoundRef.CLASSIC, back.choice().get(AlarmType.HIGH));
        }
    }

    @Test
    public void anEntryThatFailsValidationIsSkippedAndTheRestIsKept() {
        String json = "{\"own\":["
                + "{\"key\":\"" + KEY_A + "\",\"name\":\"Good\",\"file\":\"own-a\",\"size\":10},"
                + "{\"key\":\"short\",\"name\":\"Bad key\",\"file\":\"own-b\",\"size\":10},"
                + "{\"key\":\"" + KEY_B + "\",\"name\":\"Bad file\",\"file\":\"../x\",\"size\":10},"
                + "{\"key\":\"" + KEY_C + "\",\"name\":\"No size\",\"file\":\"own-c\"}"
                + "]}";
        SoundLibrary back = SoundLibraryJson.read(json, AlarmSoundChoice.allClassic());
        assertEquals(List.of(new OwnSound(KEY_A, "Good", "own-a", 10L)), back.ownSounds());
    }

    @Test
    public void unknownKeysAndUnknownAlarmNamesAreIgnored() {
        String json = "{\"future\":true,\"own\":[{\"key\":\"" + KEY_A
                + "\",\"name\":\"A\",\"file\":\"own-a\",\"size\":10,\"extra\":1}],\"pending\":{\"own\":{\"key\":\""
                + KEY_B + "\",\"name\":\"B\",\"file\":\"own-b\",\"size\":20},\"reassigned\":[\"LOW\",\"FUTURE\",7]}}";
        SoundLibrary back = SoundLibraryJson.read(json, AlarmSoundChoice.allClassic());
        assertEquals(1, back.ownSounds().size());
        assertEquals(List.of(AlarmType.LOW), back.pendingRemoval().orElseThrow().reassigned());
    }

    @Test
    public void writtenTextHoldsNoSoundBytesOnlyNamesAndSizes() {
        String json = SoundLibraryJson.write(full());
        assertTrue(json.contains(KEY_A));
        assertTrue(json.contains("Morning beep"));
        assertTrue(json.contains("content://media/internal/audio/media/6"));
    }
}
