package app.lightscout.domain.port;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** DCR-51: the sound events and their levels. */
public class EventCodeSoundTest {

    @Test
    public void soundChoiceIsInformational() {
        assertEquals(EventCode.Level.INFO, EventCode.ALARM_SOUND.level());
    }

    @Test
    public void importFailureIsAWarning() {
        assertEquals(EventCode.Level.WARN, EventCode.SOUND_IMPORT_FAIL.level());
    }
}
