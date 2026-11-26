package app.lightscout.alarm;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.SoundKind;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DCR-51 S4: the chosen alarm sound comes first in the fallback chain; the chain after it (classic tone, system alarm
 * tone, vibration only), the escalation, the call gain and the vibration stay as in release 0.1.0. A user who gave
 * every type the same sound, or a Replace that keeps the old sound, is accepted (PO decision), so the engine does not
 * change a ringing sound on a type switch.
 */
public class RingEngineSoundTest {

    private Fakes f;
    private RingEngine engine;

    @Before
    public void setUp() {
        f = new Fakes();
        f.volume.max = 10;
        f.volume.min = 0;
        f.volume.current = 2;
        engine = new RingEngine(f.volume, f.output, f.wakeLock, f.scheduler, f.logger, (type, step) -> { },
                f.clock::elapsedRealtimeMs, f.choice);
    }

    @Test
    public void everyTypeOnClassic_startsLikeRelease010() {
        for (AlarmType type : AlarmType.values()) {
            engine.start(type, 0, 2);
            assertEquals(SoundStage.PRIMARY_TONE, engine.stage());
            engine.stop();
        }
        assertFalse("the selected stage is never asked",
                f.output.asked.stream().anyMatch(s -> s.startsWith("SELECTED")));
        assertTrue("no sound event for the default", f.logger.events.isEmpty());
    }

    @Test
    public void aChosenSound_playsFirstAndIsLogged() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        engine.start(AlarmType.LOW, 0, 2);
        assertEquals(SoundStage.SELECTED_SOUND, engine.stage());
        assertEquals(SoundStage.SELECTED_SOUND, f.output.playing);
        assertEquals(AlarmType.LOW, f.output.playingType);
        assertEquals(List.of("ALARM_SOUND:OWN"), f.logger.events);
        assertTrue(f.output.vibrating);
    }

    @Test
    public void onlyTheChosenTypeUsesItsSound() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        engine.start(AlarmType.HIGH, 0, 2);
        assertEquals(SoundStage.PRIMARY_TONE, engine.stage());
        assertEquals(List.of("PRIMARY_TONE:HIGH"), f.output.asked);
    }

    @Test
    public void aChosenSoundThatCannotPlay_fallsBackToTheClassicTone() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        f.output.selectedFails = true;
        engine.start(AlarmType.LOW, 0, 2);
        assertEquals(SoundStage.PRIMARY_TONE, engine.stage());
        assertEquals(SoundStage.PRIMARY_TONE, f.output.playing);
        assertEquals(List.of("ALARM_SOUND_FALLBACK:PRIMARY_TONE"), f.logger.events);
        assertTrue(f.output.vibrating);
    }

    @Test
    public void theWholeChainStillEndsInVibrationOnly() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        f.output.selectedFails = true;
        f.output.primaryFails = true;
        f.output.systemFails = true;
        engine.start(AlarmType.LOW, 0, 2);
        assertEquals(SoundStage.VIBRATION_ONLY, engine.stage());
        assertTrue(f.output.vibrating);
        assertTrue(engine.active());
        assertEquals(List.of("ALARM_SOUND_FALLBACK:PRIMARY_TONE", "ALARM_SOUND_FALLBACK:SYSTEM_TONE",
                "ALARM_SOUND_FALLBACK:VIBRATION_ONLY"), f.logger.events);
    }

    @Test
    public void aChosenSoundThatFailsWhileRinging_movesToTheClassicTone() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        engine.start(AlarmType.LOW, 0, 2);
        engine.onPlaybackError();
        assertEquals(SoundStage.PRIMARY_TONE, engine.stage());
        assertEquals(SoundStage.PRIMARY_TONE, f.output.playing);
        assertEquals(List.of("ALARM_SOUND:OWN", "ALARM_SOUND_FALLBACK:PRIMARY_TONE"), f.logger.events);
        engine.onPlaybackError();
        assertEquals(SoundStage.SYSTEM_TONE, engine.stage());
    }

    @Test
    public void aReplaceKeepsTheSoundThatIsAlreadyRinging() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        f.chosen.put(AlarmType.URGENT_LOW, SoundKind.BUNDLED);
        engine.start(AlarmType.LOW, 0, 2);
        List<String> before = List.copyOf(f.output.asked);
        engine.switchType(AlarmType.URGENT_LOW);
        assertEquals("no new play call", before, f.output.asked);
        assertEquals(SoundStage.SELECTED_SOUND, f.output.playing);
        assertEquals(AlarmType.LOW, f.output.playingType);
    }

    @Test
    public void afterASnooze_theNextStartBeginsAgainWithTheChosenSound() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        engine.start(AlarmType.LOW, 0, 2);
        engine.silence(AlarmType.LOW);
        f.clock.elapsed += RingEngine.SNOOZE_SILENCE_GUARD_MS;
        engine.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(List.of("SELECTED_SOUND:LOW", "SELECTED_SOUND:LOW"), f.output.asked);
        assertEquals(SoundStage.SELECTED_SOUND, f.output.playing);
    }

    @Test
    public void aCallStillLowersTheChosenSoundTo30Percent() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        engine.start(AlarmType.LOW, 0, 2);
        engine.setInCall(true);
        assertEquals(0.3f, f.output.gain, 1e-6);
        engine.setInCall(false);
        assertEquals(1f, f.output.gain, 1e-6);
    }

    @Test
    public void escalationRunsTheSameWhateverThePlayingSound() {
        f.chosen.put(AlarmType.LOW, SoundKind.OWN);
        engine.start(AlarmType.LOW, 0, 2);
        f.scheduler.advance(90_000);
        assertEquals(List.of(4, 5, 6, 7, 8, 9, 10), f.volume.sets);
    }
}
