package app.lightscout.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Call / focus lifecycle across alarms (🏛️ IR-90, IR-91, IR-92): nothing sticky after a stop / snooze; the watch
 * reports the current call state on every (re)start; keep-alive focus re-request only while not held and outside a
 * call; fallback sound source order (IR-84).
 */
public class RingSessionTest {

    private Fakes f;
    private Modes modes;
    private Focus focus;
    private RingSession session;
    private int callEndedReposts;

    /** Audio-mode fake (API 33: listener band). */
    private static final class Modes implements CallWatch.ModeSource {
        int mode = CallWatch.MODE_NORMAL;
        CallWatch.ModeListener listener;

        @Override
        public int mode() {
            return mode;
        }

        @Override
        public void addModeListener(CallWatch.ModeListener l) {
            listener = l;
        }

        @Override
        public void removeModeListener() {
            listener = null;
        }

        void set(int m) {
            mode = m;
            if (listener != null) {
                listener.onMode(m);
            }
        }
    }

    /** Focus fake. */
    private static final class Focus implements CallWatch.FocusPort {
        boolean grant = true;
        int requests;

        @Override
        public boolean request() {
            requests++;
            return grant;
        }

        @Override
        public void abandon() {
            // no-op
        }
    }

    @Before
    public void setUp() {
        f = new Fakes();
        f.volume.max = 10;
        f.volume.min = 0;
        modes = new Modes();
        focus = new Focus();
        RingEngine engine = new RingEngine(f.volume, f.output, f.wakeLock, f.scheduler, f.logger, (t, s) -> { },
                f.clock::elapsedRealtimeMs);
        session = new RingSession(engine, 33, modes, focus, f.scheduler, () -> callEndedReposts++);
    }

    @Test
    public void stopDuringCall_nextAlarmAfterCall_fullEscalation() {
        session.start(AlarmType.LOW, 0, 2);
        modes.set(CallWatch.MODE_IN_CALL);
        assertEquals(0.3f, f.output.gain, 1e-6);
        session.stop();                     // snoozed during the call
        modes.set(CallWatch.MODE_NORMAL);   // call ends while nothing rings
        session.start(AlarmType.HIGH, 0, 2);
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertFalse(session.engine().inCall());
        assertEquals(0, callEndedReposts);
    }

    @Test
    public void stopWhileFocusLowered_nextAlarmFull() {
        session.start(AlarmType.LOW, 0, 2);
        session.onFocusChange(CallWatch.FOCUS_LOSS);
        assertEquals(0.3f, f.output.gain, 1e-6);
        session.stop();
        session.start(AlarmType.LOW, 0, 2);
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertFalse(session.engine().focusLowered());
    }

    @Test
    public void silencedDuringCall_failSafeAfterCall_full() {
        session.start(AlarmType.LOW, 0, 2);
        modes.set(CallWatch.MODE_IN_CALL);
        session.silence(AlarmType.LOW);      // Q-71 local silence during the call
        modes.set(CallWatch.MODE_NORMAL);    // the call ends while silenced (watch stopped)
        f.clock.advanceMs(RingEngine.SNOOZE_SILENCE_GUARD_MS);
        session.keepAlive(AlarmType.LOW, 0, 2);   // fail-safe restart
        assertTrue(session.engine().active());
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertFalse(session.engine().inCall());
    }

    @Test
    public void newAlarmWhileCallStillActive_thirtyPercent() {
        session.start(AlarmType.LOW, 0, 2);
        modes.set(CallWatch.MODE_IN_CALL);
        session.stop();
        session.start(AlarmType.URGENT_LOW, 0, 2);   // the call is still active
        assertEquals(0.3f, f.output.gain, 1e-6);
        assertTrue(f.output.vibrating);
        modes.set(CallWatch.MODE_NORMAL);
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertEquals(1, callEndedReposts);
    }

    @Test
    public void failSafeRestartDuringStillActiveCall_thirtyPercent() {
        session.start(AlarmType.LOW, 0, 2);
        modes.set(CallWatch.MODE_IN_CALL);
        session.silence(AlarmType.LOW);
        f.clock.advanceMs(RingEngine.SNOOZE_SILENCE_GUARD_MS);
        session.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(0.3f, f.output.gain, 1e-6);
    }

    @Test
    public void keepAliveDuringCall_neverRequestsFocus() {
        focus.grant = false;
        session.start(AlarmType.LOW, 0, 2);
        int afterStart = focus.requests;
        modes.set(CallWatch.MODE_IN_CALL);
        session.keepAlive(AlarmType.LOW, 0, 2);
        session.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(afterStart, focus.requests);   // IR-91: no focus taken from the phone / VoIP app
        modes.set(CallWatch.MODE_NORMAL);
        assertEquals(afterStart + 1, focus.requests);
    }

    @Test
    public void keepAlive_reRequestsFocusOnlyWhileNotHeld() {
        focus.grant = false;
        session.start(AlarmType.LOW, 0, 2);
        assertEquals(1, focus.requests);
        session.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(2, focus.requests);   // not held → re-request (IR-82)
        focus.grant = true;
        session.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(3, focus.requests);
        session.keepAlive(AlarmType.LOW, 0, 2);
        session.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(3, focus.requests);   // held → no more requests
        session.onFocusChange(CallWatch.FOCUS_LOSS);
        session.keepAlive(AlarmType.LOW, 0, 2);
        assertEquals(4, focus.requests);
        assertEquals(1.0f, f.output.gain, 1e-6);
    }

    @Test
    public void fallbackSoundSourceOrder() {
        assertEquals(SoundSource.RAW_ALARM_TONE, SoundSource.of(SoundStage.PRIMARY_TONE));
        assertEquals(SoundSource.SYSTEM_DEFAULT_ALARM_URI, SoundSource.of(SoundStage.SYSTEM_TONE));
        assertNull(SoundSource.of(SoundStage.VIBRATION_ONLY));
        assertEquals(SoundStage.SYSTEM_TONE, SoundStage.PRIMARY_TONE.next());
        assertEquals(SoundStage.VIBRATION_ONLY, SoundStage.SYSTEM_TONE.next());
    }

    @Test
    public void playerMapsSystemSourceToDefaultAlarmAlertUriOnly() throws IOException {
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/app/lightscout/alarm/AlarmPlayer.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("source == SoundSource.SYSTEM_DEFAULT_ALARM_URI"));
        assertTrue(src.contains("Settings.System.DEFAULT_ALARM_ALERT_URI"));
        assertFalse(src.contains("getActualDefaultRingtoneUri"));
        assertFalse(src.contains("RingtoneManager"));
    }
}
