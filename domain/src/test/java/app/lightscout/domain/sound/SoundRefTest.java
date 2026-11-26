package app.lightscout.domain.sound;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** DCR-51: a sound reference is stored as text; anything that does not parse reads as no reference. */
public class SoundRefTest {

    private static final String KEY = "0123456789abcdef";

    @Test
    public void roundTripsEveryKind() {
        for (SoundRef ref : new SoundRef[] {SoundRef.CLASSIC, SoundRef.bundled("rising_beeps"),
                SoundRef.phone(KEY), SoundRef.own(KEY)}) {
            assertEquals(ref, SoundRef.parse(ref.encode()).orElseThrow());
        }
    }

    @Test
    public void encodesToTheStoredForms() {
        assertEquals("classic", SoundRef.CLASSIC.encode());
        assertEquals("bundled:chime", SoundRef.bundled("chime").encode());
        assertEquals("phone:" + KEY, SoundRef.phone(KEY).encode());
        assertEquals("own:" + KEY, SoundRef.own(KEY).encode());
    }

    @Test
    public void refusesMalformedText() {
        String[] bad = {null, "", " ", "Classic", "classic:", "classic:x", "bundled:", "bundled:UPPER",
                "bundled:with space", "bundled:" + "a".repeat(25), "phone:", "phone:abc", "phone:" + KEY.toUpperCase(),
                "phone:0123456789abcdeg", "own:" + KEY + "0", "own:../../x", "file:" + KEY, "own:" + KEY + "\n"};
        for (String text : bad) {
            assertTrue("should be refused: " + text, SoundRef.parse(text).isEmpty());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRefusesABadKey() {
        SoundRef.own("../escape");
    }

    @Test
    public void classicConstantHasNoKey() {
        assertEquals(SoundKind.CLASSIC, SoundRef.CLASSIC.kind());
        assertEquals("", SoundRef.CLASSIC.key());
    }
}
