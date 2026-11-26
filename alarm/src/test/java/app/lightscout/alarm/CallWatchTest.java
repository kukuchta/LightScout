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
 * CP-07 §9 „JVM — call handling (F10)” on both API bands with an injected SDK level (CP-00 §10 CR-04), per 🏛️ IR-82 /
 * Q-72 / Q-73: the call state comes only from the audio mode ({@code IN_CALL} / {@code IN_COMMUNICATION} start,
 * {@code NORMAL} end; listener 31+, 5 s polling 29–30); focus only lowers the level (transient / CAN_DUCK ignored,
 * permanent loss outside a call → 30 % without re-post, re-request on mode → NORMAL and on keep-alive, a refused
 * request with mode NORMAL is not a call).
 */
public class CallWatchTest {

    private Fakes f;
    private final List<String> events = new ArrayList<>();
    private FakeModes modes;
    private FakeFocus focus;

    /** AudioManager mode fake. */
    private static final class FakeModes implements CallWatch.ModeSource {
        int mode;
        CallWatch.ModeListener listener;
        int adds;
        int removes;
        int reads;

        @Override
        public int mode() {
            reads++;
            return mode;
        }

        @Override
        public void addModeListener(CallWatch.ModeListener l) {
            listener = l;
            adds++;
        }

        @Override
        public void removeModeListener() {
            listener = null;
            removes++;
        }
    }

    /** Focus request fake. */
    private static final class FakeFocus implements CallWatch.FocusPort {
        boolean grant = true;
        int requests;
        int abandons;

        @Override
        public boolean request() {
            requests++;
            return grant;
        }

        @Override
        public void abandon() {
            abandons++;
        }
    }

    private final CallWatch.Listener recorder = new CallWatch.Listener() {
        @Override
        public void onCallStateChanged(boolean inCall) {
            events.add(inCall ? "call" : "end");
        }

        @Override
        public void onFocusLowered(boolean lowered) {
            events.add(lowered ? "lowered" : "level");
        }
    };

    @Before
    public void setUp() {
        f = new Fakes();
        modes = new FakeModes();
        focus = new FakeFocus();
    }

    private CallWatch watch(int sdk) {
        return new CallWatch(sdk, modes, focus, f.scheduler, recorder);
    }

    @Test
    public void detection_perApiBand() {
        assertEquals(CallWatch.Detection.POLLING, CallWatch.detectionFor(29));
        assertEquals(CallWatch.Detection.POLLING, CallWatch.detectionFor(30));
        assertEquals(CallWatch.Detection.LISTENER, CallWatch.detectionFor(31));
        assertEquals(CallWatch.Detection.LISTENER, CallWatch.detectionFor(36));
    }

    @Test
    public void callModes() {
        assertTrue(CallWatch.isCallMode(CallWatch.MODE_IN_CALL));
        assertTrue(CallWatch.isCallMode(CallWatch.MODE_IN_COMMUNICATION));
        assertFalse(CallWatch.isCallMode(CallWatch.MODE_NORMAL));
        assertFalse(CallWatch.isCallMode(1));   // MODE_RINGTONE
    }

    @Test
    public void band1_pollsEvery5s_whileRinging() {
        CallWatch w = watch(29);
        w.start();
        assertEquals(0, modes.adds);
        int readsAtStart = modes.reads;
        f.scheduler.advance(4_999);
        assertEquals(readsAtStart, modes.reads);
        f.scheduler.advance(1);
        assertEquals(readsAtStart + 1, modes.reads);
        modes.mode = CallWatch.MODE_IN_CALL;
        f.scheduler.advance(5_000);
        assertEquals(List.of("call"), events);
        modes.mode = CallWatch.MODE_NORMAL;
        f.scheduler.advance(5_000);
        assertEquals(List.of("call", "end"), events);
        w.stop();
        int readsAtStop = modes.reads;
        f.scheduler.advance(60_000);
        assertEquals(readsAtStop, modes.reads);   // no polling after Snooze
    }

    @Test
    public void band2_usesModeListener_noPolling() {
        CallWatch w = watch(31);
        w.start();
        assertEquals(1, modes.adds);
        assertEquals(0, f.scheduler.pending());
        modes.listener.onMode(CallWatch.MODE_IN_COMMUNICATION);   // VoIP call
        assertEquals(List.of("call"), events);
        modes.listener.onMode(CallWatch.MODE_NORMAL);
        assertEquals(List.of("call", "end"), events);
        w.stop();
        assertEquals(1, modes.removes);
        assertNull(modes.listener);
        assertEquals(1, focus.abandons);
    }

    @Test
    public void startDuringCall_reportsCallAtOnce() {
        modes.mode = CallWatch.MODE_IN_CALL;
        watch(33).start();
        assertEquals(List.of("call"), events);
    }

    @Test
    public void transientLossAndCanDuck_areIgnored() {
        CallWatch w = watch(33);
        w.start();
        w.onFocusChange(CallWatch.FOCUS_LOSS_TRANSIENT);
        w.onFocusChange(CallWatch.FOCUS_LOSS_TRANSIENT_CAN_DUCK);
        w.onFocusChange(CallWatch.FOCUS_GAIN);
        assertTrue(events.isEmpty());
        assertFalse(w.inCall());
        assertFalse(w.lowered());
    }

    @Test
    public void permanentLossOutsideCall_lowersWithoutCallEvent_keepAliveRegains() {
        CallWatch w = watch(33);
        w.start();
        w.onFocusChange(CallWatch.FOCUS_LOSS);
        assertEquals(List.of("lowered"), events);
        assertFalse(w.inCall());
        w.ensureFocus();   // keep-alive re-request, granted
        assertEquals(List.of("lowered", "level"), events);
        assertTrue(w.focusHeld());
        assertEquals(2, focus.requests);
    }

    @Test
    public void refusedRequestWithModeNormal_isNotACall_noLowering_retriedOnKeepAlive() {
        focus.grant = false;
        CallWatch w = watch(33);
        w.start();
        assertTrue(events.isEmpty());
        assertFalse(w.inCall());
        assertFalse(w.lowered());
        w.ensureFocus();
        w.ensureFocus();
        assertEquals(3, focus.requests);   // retried on every keep-alive while not held
        focus.grant = true;
        w.ensureFocus();
        assertTrue(w.focusHeld());
        w.ensureFocus();
        assertEquals(4, focus.requests);   // held: no more requests
    }

    @Test
    public void callEnd_reRequestsFocus() {
        modes.mode = CallWatch.MODE_IN_CALL;
        focus.grant = false;
        CallWatch w = watch(34);
        w.start();
        assertEquals(0, focus.requests);   // IR-91: no request while a call is active
        w.ensureFocus();                   // keep-alive during the call
        assertEquals(0, focus.requests);
        focus.grant = true;
        modes.listener.onMode(CallWatch.MODE_NORMAL);
        assertEquals(1, focus.requests);
        assertTrue(w.focusHeld());
        assertEquals(List.of("call", "end"), events);
    }

    /** Engine + surface wired like {@code AlarmPlayer}. */
    private final class Rig {
        final AlarmScreenModel screen = new AlarmScreenModel();
        final AlarmSurfaceController surface = new AlarmSurfaceController(f.notifications, f.channels, screen,
                f.screenOff);
        final RingEngine engine = new RingEngine(f.volume, f.output, f.wakeLock, f.scheduler, f.logger,
                (t, s) -> { }, f.clock::elapsedRealtimeMs, f.choice);
        final CallWatch w;

        Rig(int sdk) {
            w = new CallWatch(sdk, modes, focus, f.scheduler, new CallWatch.Listener() {
                @Override
                public void onCallStateChanged(boolean inCall) {
                    engine.setInCall(inCall);
                    if (!inCall && engine.active()) {
                        surface.onCallEnded();
                    }
                }

                @Override
                public void onFocusLowered(boolean lowered) {
                    engine.setFocusLowered(lowered);
                }
            });
        }

        void ring(AlarmType type) {
            surface.show(Fakes.view(type, 50));
            engine.start(type, 0, 5);
            w.start();
            f.notifications.log.clear();
        }

        long fsiReposts() {
            return f.notifications.log.stream().filter(e -> e.endsWith(",fsi)")).count();
        }
    }

    @Test
    public void inCall_gain30_vibrationContinues_callEnd_fullGain_oneFsiRepost() {
        Rig r = new Rig(30);
        r.ring(AlarmType.URGENT_LOW);
        modes.mode = CallWatch.MODE_IN_CALL;
        f.scheduler.advance(5_000);
        assertEquals(0.3f, f.output.gain, 1e-6);
        assertTrue(f.output.vibrating);
        assertEquals(1, f.notifications.active());
        assertEquals(0, r.fsiReposts());
        modes.mode = CallWatch.MODE_NORMAL;
        f.scheduler.advance(5_000);
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertEquals(List.of("cancel", "post(alarms,URGENT_LOW,fsi)"), f.notifications.log);
    }

    @Test
    public void callEndsAfterRefusedRequest_fullGain_exactlyOneFsiRepost() {
        modes.mode = CallWatch.MODE_IN_CALL;   // the alarm starts during a call; the focus request is refused
        focus.grant = false;
        Rig r = new Rig(35);
        r.ring(AlarmType.LOW);
        assertEquals(0.3f, f.output.gain, 1e-6);
        modes.listener.onMode(CallWatch.MODE_NORMAL);   // still refused after the call: not a call (Q-72)
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertEquals(1, r.fsiReposts());
        r.w.ensureFocus();   // later keep-alives: no further re-posts
        r.w.ensureFocus();
        assertEquals(1, r.fsiReposts());
        assertEquals(1.0f, f.output.gain, 1e-6);
    }

    @Test
    public void permanentLoss_then_keepAliveRegains_fullGain_noRepost() {
        Rig r = new Rig(33);
        r.ring(AlarmType.HIGH);
        r.w.onFocusChange(CallWatch.FOCUS_LOSS);
        assertEquals(0.3f, f.output.gain, 1e-6);
        assertEquals(0, r.fsiReposts());
        r.w.ensureFocus();
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertTrue(f.notifications.log.isEmpty());
    }

    @Test
    public void canDuck_noGainChange_noRepost() {
        Rig r = new Rig(33);
        r.ring(AlarmType.HIGH);
        r.w.onFocusChange(CallWatch.FOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(1.0f, f.output.gain, 1e-6);
        r.w.onFocusChange(CallWatch.FOCUS_GAIN);
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertTrue(f.notifications.log.isEmpty());
    }

    @Test
    public void refusedRequestWithModeNormal_fullD023Escalation() {
        focus.grant = false;
        f.volume.max = 10;
        f.volume.min = 0;
        Rig r = new Rig(36);
        r.ring(AlarmType.URGENT_LOW);
        assertEquals(1.0f, f.output.gain, 1e-6);
        f.scheduler.advance(90_000);
        assertEquals(10, f.volume.current);
        assertEquals(1.0f, f.output.gain, 1e-6);
        assertEquals(0, r.fsiReposts());
    }
}
