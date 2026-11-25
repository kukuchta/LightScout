package app.lightscout.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Player logic: escalation timing, F8 user volume change, VR-12 gain fallback, F10 in-call gain, WF-02 step 3 sound
 * fallback chain, F16 wake lock (CP-07 §9 rows „escalation schedule”, „user volume change during ringing”,
 * „call handling”, „sound fallback chain”).
 */
public class RingEngineTest {

    private Fakes f;
    private RingEngine engine;
    private final List<String> steps = new ArrayList<>();

    @Before
    public void setUp() {
        f = new Fakes();
        f.volume.max = 10;
        f.volume.min = 0;
        f.volume.current = 2;
        engine = new RingEngine(f.volume, f.output, f.wakeLock, f.scheduler, f.logger,
                (type, step) -> steps.add(type + ":" + step), f.clock::elapsedRealtimeMs);
    }

    @Test
    public void start_vibratesFromZero_setsStartVolume_playsPrimaryTone() {
        engine.start(AlarmType.LOW, 0, 2);
        assertTrue(f.output.vibrating);
        assertEquals(SoundStage.PRIMARY_TONE, f.output.playing);
        assertEquals(4, f.volume.current);   // max(user 20 %, 40 %) of 10
        assertEquals(List.of("vibrate", "set(4)"), f.ops);   // vibration from t = 0, before the first step
    }

    @Test
    public void escalation_plusOneStepEvery15s_cappedAtMaxAfter90s() {
        engine.start(AlarmType.LOW, 0, 2);
        f.scheduler.advance(14_999);
        assertEquals(4, f.volume.current);
        f.scheduler.advance(1);
        assertEquals(5, f.volume.current);
        f.scheduler.advance(75_000);   // t = 90 s
        assertEquals(10, f.volume.current);
        assertFalse(engine.escalating());
        assertEquals(0, f.scheduler.pending());
        f.scheduler.advance(60_000);
        assertEquals(List.of(4, 5, 6, 7, 8, 9, 10), f.volume.sets);
        assertEquals(List.of("LOW:1", "LOW:2", "LOW:3", "LOW:4", "LOW:5", "LOW:6"), steps);
    }

    @Test
    public void userVolumeChange_stopsEscalation_keepsRinging() {
        engine.start(AlarmType.LOW, 0, 2);
        f.scheduler.advance(15_000);
        assertEquals(5, f.volume.current);
        f.volume.current = 3;   // the user pressed volume down outside S-09
        f.scheduler.advance(15_000);
        assertFalse(engine.escalating());
        assertEquals(3, f.volume.current);
        f.scheduler.advance(120_000);
        assertEquals(3, f.volume.current);
        assertTrue(engine.active());
        assertTrue(f.output.vibrating);
    }

    @Test
    public void streamVolumeRefused_fallsBackToPlayerGainEscalation() {
        f.volume.refuse = true;
        engine.start(AlarmType.HIGH, 0, 2);
        assertTrue(engine.gainMode());
        assertEquals(0.4f, f.output.gain, 1e-6);
        f.scheduler.advance(15_000);
        assertEquals(0.5f, f.output.gain, 1e-6);
        f.scheduler.advance(75_000);
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertFalse(engine.escalating());
        assertEquals(2, f.volume.current);   // stream volume untouched
    }

    @Test
    public void inCall_gainThirtyPercent_vibrationContinues_restoredAfter() {
        engine.start(AlarmType.URGENT_LOW, 0, 2);
        engine.setInCall(true);
        assertEquals(0.3f, f.output.gain, 1e-6);
        assertTrue(f.output.vibrating);
        assertEquals(SoundStage.PRIMARY_TONE, f.output.playing);
        engine.setInCall(false);
        assertEquals(1.0f, f.output.gain, 1e-6);
    }

    @Test
    public void inCall_inGainFallback_usesLowerOfBoth() {
        f.volume.refuse = true;
        engine.start(AlarmType.URGENT_LOW, 0, 2);
        engine.setInCall(true);
        assertEquals(0.3f, f.output.gain, 1e-6);
    }

    @Test
    public void soundFileError_fallsBackToSystemTone() {
        f.output.primaryFails = true;
        engine.start(AlarmType.LOW, 0, 2);
        assertEquals(SoundStage.SYSTEM_TONE, engine.stage());
        assertEquals(SoundStage.SYSTEM_TONE, f.output.playing);
        assertTrue(f.logger.events.contains("ALARM_SOUND_FALLBACK:SYSTEM_TONE"));
    }

    @Test
    public void toneError_fallsBackToVibrationOnly() {
        f.output.primaryFails = true;
        f.output.systemFails = true;
        engine.start(AlarmType.LOW, 0, 2);
        assertEquals(SoundStage.VIBRATION_ONLY, engine.stage());
        assertNull(f.output.playing);
        assertTrue(f.output.vibrating);
        assertTrue(engine.active());
        assertEquals(List.of("ALARM_SOUND_FALLBACK:SYSTEM_TONE", "ALARM_SOUND_FALLBACK:VIBRATION_ONLY"),
                f.logger.events);
    }

    @Test
    public void playbackErrorWhilePlaying_movesDownTheChain() {
        engine.start(AlarmType.LOW, 0, 2);
        engine.onPlaybackError();
        assertEquals(SoundStage.SYSTEM_TONE, engine.stage());
        engine.onPlaybackError();
        assertEquals(SoundStage.VIBRATION_ONLY, engine.stage());
        assertTrue(f.output.vibrating);
        engine.onPlaybackError();
        assertEquals(SoundStage.VIBRATION_ONLY, engine.stage());
    }

    @Test
    public void wakeLock_acquiredWithTwoMinuteTimeout_reacquiredOnKeepAlive_releasedOnStop() {
        engine.start(AlarmType.LOW, 0, 2);
        assertTrue(f.wakeLock.held);
        assertEquals(120_000L, f.wakeLock.lastTimeout);
        engine.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(2, f.wakeLock.acquires);
        engine.stop();
        assertFalse(f.wakeLock.held);
        assertFalse(f.output.vibrating);
        assertNull(f.output.playing);
    }

    @Test
    public void refreshVibration_reissuesWhileRinging_neverAfterSilenceOrStop() {
        engine.start(AlarmType.LOW, 0, 2);
        f.output.vibrating = false;   // the OS cancelled it on screen off (RISK-25)
        engine.refreshVibration();
        assertTrue(f.output.vibrating);
        engine.silence(AlarmType.LOW);
        f.output.vibrating = false;
        engine.refreshVibration();
        assertFalse(f.output.vibrating);
        engine.start(AlarmType.LOW, 0, 2);
        engine.stop();
        engine.refreshVibration();
        assertFalse(f.output.vibrating);
    }

    @Test
    public void keepAlive_whilePlaying_doesNotRestartEscalation() {
        engine.start(AlarmType.LOW, 0, 2);
        f.scheduler.advance(30_000);
        engine.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(2, engine.currentStep());
        assertEquals(1, f.output.plays);
    }

    @Test
    public void keepAlive_whenNotPlaying_startsAtGivenStep() {
        engine.keepAlive(AlarmType.NO_DATA, 3, 2);
        assertTrue(engine.active());
        assertEquals(3, engine.currentStep());
        assertEquals(7, f.volume.current);
    }

    @Test
    public void switchType_keepsLevel_reportsStepForNewType() {
        engine.start(AlarmType.LOW, 0, 2);
        f.scheduler.advance(30_000);
        engine.switchType(AlarmType.URGENT_LOW);
        assertEquals(2, engine.currentStep());
        assertEquals(6, f.volume.current);
        assertEquals("URGENT_LOW:2", steps.get(steps.size() - 1));
        assertEquals(1, f.output.plays);
    }

    @Test
    public void silence_onlyForTheSnoozedType() {
        engine.start(AlarmType.LOW, 0, 2);
        engine.silence(AlarmType.HIGH);
        assertTrue(engine.active());
        engine.silence(AlarmType.LOW);
        assertFalse(engine.active());
        assertFalse(f.output.vibrating);
        assertEquals(0, f.scheduler.pending());
    }

    @Test
    public void silence_releasesWakeLock_whenNothingRings() {
        engine.start(AlarmType.LOW, 0, 2);
        assertTrue(f.wakeLock.held);
        engine.silence(AlarmType.LOW);
        assertFalse(f.wakeLock.held);   // IR-89
    }

    @Test
    public void snoozedType_keepAliveIgnored_otherTypeRingsAtOnce() {
        engine.start(AlarmType.LOW, 0, 2);
        engine.silence(AlarmType.LOW);
        engine.keepAlive(AlarmType.LOW, 0, 2);
        assertFalse(engine.active());
        assertEquals(AlarmType.LOW, engine.snoozed());
        engine.keepAlive(AlarmType.URGENT_LOW, 0, 2);
        assertTrue(engine.active());
        assertEquals(AlarmType.URGENT_LOW, engine.type());
        assertNull(engine.snoozed());
    }

    @Test
    public void snoozedType_failSafeAfterThreeMinutes() {
        engine.start(AlarmType.LOW, 0, 2);
        engine.silence(AlarmType.LOW);
        f.clock.advanceMs(RingEngine.SNOOZE_SILENCE_GUARD_MS - 1);
        engine.keepAlive(AlarmType.LOW, 0, 2);
        assertFalse(engine.active());
        f.clock.advanceMs(1);
        engine.keepAlive(AlarmType.LOW, 0, 2);
        assertTrue(engine.active());
        assertTrue(f.wakeLock.held);
    }

    @Test
    public void focusLowered_thirtyPercent_backToEscalationLevel() {
        engine.start(AlarmType.HIGH, 0, 2);
        engine.setFocusLowered(true);
        assertEquals(0.3f, f.output.gain, 1e-6);
        engine.setFocusLowered(false);
        assertEquals(1.0f, f.output.gain, 1e-6);
    }

    @Test
    public void restoreVolume_setsSavedValue() {
        engine.start(AlarmType.LOW, 0, 2);
        engine.stop();
        engine.restoreVolume(2);
        assertEquals(2, f.volume.current);
    }
}
