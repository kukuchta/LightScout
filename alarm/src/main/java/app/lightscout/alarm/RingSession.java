package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;

/**
 * Player-thread orchestration of {@link RingEngine} and {@link CallWatch} (CMP-41; IR-82, IR-90, IR-91, IR-92), free
 * of Android types so that the call / focus lifecycle across alarms is JVM-tested. {@code AlarmPlayer} only hops onto
 * the player thread and provides the Android seams.
 *
 * <ul>
 *   <li>Every (re)start of the engine restarts the call watch, which reports the current mode afresh: a call still
 *       active gives 30 %, otherwise the full D-023 level. Nothing is sticky from a previous alarm (IR-90).</li>
 *   <li>A keep-alive while playing re-requests focus only while it is not held and no call is active (IR-82,
 *       IR-91).</li>
 *   <li>GIR-5: call start gives 30 %; call end gives full gain and one CH-01 re-post with the FSI
 *       ({@code onCallEnded}). Focus only lowers the level and never re-posts.</li>
 * </ul>
 * Not thread-safe; player thread only.
 */
final class RingSession implements AlarmSound {

    private final RingEngine engine;
    private final CallWatch calls;
    private final Runnable onCallEnded;

    /**
     * Creates the session.
     *
     * @param engine      player logic
     * @param sdkInt      {@code Build.VERSION.SDK_INT} (a fake in tests)
     * @param modes       audio-mode seam
     * @param focus       audio-focus seam
     * @param scheduler   player-thread scheduler
     * @param onCallEnded GIR-5 call end: re-post CH-01 with the FSI
     */
    RingSession(RingEngine engine, int sdkInt, CallWatch.ModeSource modes, CallWatch.FocusPort focus,
                Scheduler scheduler, Runnable onCallEnded) {
        this.engine = engine;
        this.onCallEnded = onCallEnded;
        this.calls = new CallWatch(sdkInt, modes, focus, scheduler, new CallWatch.Listener() {
            @Override
            public void onCallStateChanged(boolean inCall) {
                engine.setInCall(inCall);
                if (!inCall && engine.active()) {
                    RingSession.this.onCallEnded.run();
                }
            }

            @Override
            public void onFocusLowered(boolean lowered) {
                engine.setFocusLowered(lowered);   // level only, never a re-post (IR-82)
            }
        });
    }

    @Override
    public void start(AlarmType type, int step, int userVolume) {
        engine.start(type, step, userVolume);
        restartCalls();
    }

    @Override
    public void switchType(AlarmType type) {
        engine.switchType(type);
    }

    @Override
    public void keepAlive(AlarmType type, int step, int userVolume) {
        boolean wasActive = engine.active();
        engine.keepAlive(type, step, userVolume);
        if (!engine.active()) {
            return;   // the snoozed type stays silent (Q-71)
        }
        if (wasActive) {
            calls.ensureFocus();   // re-request while not held, never during a call
        } else {
            restartCalls();        // fail-safe / restart: report the current call state afresh
        }
    }

    @Override
    public void silence(AlarmType type) {
        engine.silence(type);
        if (!engine.active()) {
            calls.stop();
        }
    }

    @Override
    public void stop() {
        engine.stop();
        calls.stop();
    }

    @Override
    public void restoreVolume(int volume) {
        engine.restoreVolume(volume);
    }

    @Override
    public int currentVolume() {
        return engine.currentVolume();
    }

    @Override
    public int currentStep() {
        return engine.currentStep();
    }

    /**
     * Audio-focus change from the platform listener.
     *
     * @param focusChange {@code AudioManager.AUDIOFOCUS_*}
     */
    void onFocusChange(int focusChange) {
        calls.onFocusChange(focusChange);
    }

    /**
     * Returns the engine.
     *
     * @return engine
     */
    RingEngine engine() {
        return engine;
    }

    /**
     * Returns the call watch.
     *
     * @return call watch
     */
    CallWatch calls() {
        return calls;
    }

    private void restartCalls() {
        calls.stop();
        calls.start();
    }
}
