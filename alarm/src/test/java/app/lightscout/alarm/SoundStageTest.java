package app.lightscout.alarm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** DCR-51 S4: the chosen sound is the first link of the sound fallback chain (WF-02 step 3, R-80). */
public class SoundStageTest {

    @Test
    public void theChainRunsFromTheChosenSoundDownToVibrationOnly() {
        assertEquals(SoundStage.PRIMARY_TONE, SoundStage.SELECTED_SOUND.next());
        assertEquals(SoundStage.SYSTEM_TONE, SoundStage.PRIMARY_TONE.next());
        assertEquals(SoundStage.VIBRATION_ONLY, SoundStage.SYSTEM_TONE.next());
        assertEquals(SoundStage.VIBRATION_ONLY, SoundStage.VIBRATION_ONLY.next());
    }

    @Test
    public void theChosenSoundMapsToItsOwnSource() {
        assertEquals(SoundSource.SELECTED, SoundSource.of(SoundStage.SELECTED_SOUND));
        assertEquals(SoundSource.RAW_ALARM_TONE, SoundSource.of(SoundStage.PRIMARY_TONE));
        assertEquals(SoundSource.SYSTEM_DEFAULT_ALARM_URI, SoundSource.of(SoundStage.SYSTEM_TONE));
    }

    @Test
    public void theStageNamesAreTheLogDetails() {
        assertEquals("SELECTED_SOUND", SoundStage.SELECTED_SOUND.name());
        assertEquals("PRIMARY_TONE", SoundStage.PRIMARY_TONE.name());
    }
}
