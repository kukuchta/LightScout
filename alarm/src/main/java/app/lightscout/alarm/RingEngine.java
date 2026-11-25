package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

import java.util.function.LongSupplier;

/**
 * Player logic of CMP-41 (CP-07 §8.2–§8.4; D-023, F8, F10, F16, WF-02 step 3), free of Android types so that the
 * escalation schedule, the volume rules, the call gain and the sound fallback chain are JVM-tested with fakes. Runs on
 * the player thread only ({@code AlarmPlayer} hops there); not thread-safe by itself.
 *
 * <ul>
 *   <li>Escalation: the alarm-stream volume follows {@link EscalationSchedule#level} from the start step, one step
 *       every 15 s, until the maximum. A user volume change during ringing (the stream no longer has the value this
 *       engine set) stops the escalation (F8); the restore still uses the saved value (owned by the caller).</li>
 *   <li>VR-12 fallback: when the stream volume cannot be set, the player gain escalates within the current stream
 *       volume.</li>
 *   <li>Calls (F10): gain 30 % while a call is active (audio mode) or the focus is permanently lost outside a call
 *       (IR-82); vibration continues.</li>
 *   <li>Snooze (Q-71, IR-87): {@link #silence} records the snoozed type; keep-alives of that type are ignored here, on
 *       the player thread, so the check and the restart are atomic. A start / keep-alive / switch of another type
 *       rings at once; after {@link #SNOOZE_SILENCE_GUARD_MS} without {@code Stop} the type rings again.</li>
 *   <li>Sound fallback chain (R-80): tone → system alarm tone → vibration only, logged as
 *       {@code ALARM_SOUND_FALLBACK:<stage>}.</li>
 *   <li>Wake lock (F16): acquired with a 2-min timeout on start and on every keep-alive, released on stop.</li>
 * </ul>
 */
final class RingEngine implements AlarmSound {

    /** Partial wake-lock timeout per acquisition (F16). */
    static final long WAKE_LOCK_TIMEOUT_MS = 120_000L;

    /**
     * Longest local silence after a Snooze request without the IF-25 {@code Stop} (fail-safe, SR-37; Q-71: 3 min,
     * because a tick can hold {@code monitorExecutor} up to 120 s and the snoozed type must not ring again — AL-5).
     */
    static final long SNOOZE_SILENCE_GUARD_MS = 180_000L;

    /**
     * Alarm-stream volume seam ({@code AudioManager.STREAM_ALARM}).
     */
    interface Volume {

        /**
         * Returns the current stream volume.
         *
         * @return index
         */
        int current();

        /**
         * Returns the stream minimum.
         *
         * @return index
         */
        int min();

        /**
         * Returns the stream maximum.
         *
         * @return index
         */
        int max();

        /**
         * Sets the stream volume.
         *
         * @param index the index
         * @return {@code false} when the system refused or ignored the change (VR-12 fallback)
         */
        boolean set(int index);
    }

    /**
     * Sound and vibration output seam ({@code MediaPlayer} with {@code USAGE_ALARM}, {@code Vibrator}).
     */
    interface Output {

        /**
         * Starts looping playback of a stage.
         *
         * @param stage {@link SoundStage#PRIMARY_TONE} or {@link SoundStage#SYSTEM_TONE}
         * @return {@code false} when the source cannot be played
         */
        boolean play(SoundStage stage);

        /**
         * Sets the player gain.
         *
         * @param gain 0…1
         */
        void setGain(float gain);

        /** Stops and releases playback. */
        void stopPlayback();

        /** Starts the repeating alarm vibration (800 ms on / 400 ms off, R-80). */
        void startVibration();

        /** Stops the vibration. */
        void stopVibration();
    }

    /**
     * Partial wake-lock seam.
     */
    interface WakeLock {

        /**
         * Acquires (or re-arms) the wake lock.
         *
         * @param timeoutMs timeout
         */
        void acquire(long timeoutMs);

        /** Releases the wake lock. */
        void release();
    }

    /**
     * Receives escalation progress (persisted by the runtime on {@code monitorExecutor}, R-35).
     */
    interface StepListener {

        /**
         * The escalation step of a ringing type changed.
         *
         * @param type the type
         * @param step the new step
         */
        void onStep(AlarmType type, int step);
    }

    private final Volume volume;
    private final Output output;
    private final WakeLock wakeLock;
    private final Scheduler scheduler;
    private final Logger logger;
    private final StepListener stepListener;
    private final LongSupplier elapsedMs;

    private boolean active;
    private AlarmType type;
    private int step;
    private int userVolume;
    private int lastSet = -1;
    private boolean escalating;
    private boolean gainMode;
    private boolean inCall;
    private boolean focusLowered;
    private AlarmType snoozed;
    private long snoozedAtMs;
    private SoundStage stage = SoundStage.PRIMARY_TONE;
    private Scheduler.Cancellable tick;

    /**
     * Creates the engine.
     *
     * @param volume       alarm-stream volume seam
     * @param output       sound and vibration seam
     * @param wakeLock     partial wake-lock seam
     * @param scheduler    player-thread scheduler
     * @param logger       IF-66
     * @param stepListener escalation progress receiver
     * @param elapsedMs    monotonic time (IF-15 {@code elapsedRealtimeMs}) for the snooze fail-safe
     */
    RingEngine(Volume volume, Output output, WakeLock wakeLock, Scheduler scheduler, Logger logger,
               StepListener stepListener, LongSupplier elapsedMs) {
        this.volume = volume;
        this.output = output;
        this.wakeLock = wakeLock;
        this.scheduler = scheduler;
        this.logger = logger;
        this.stepListener = stepListener;
        this.elapsedMs = elapsedMs;
    }

    @Override
    public void start(AlarmType newType, int startStep, int savedUserVolume) {
        if (active) {
            stopOutput();
        }
        active = true;
        snoozed = null;
        inCall = false;         // IR-90: the call watch reports the current state right after the start
        focusLowered = false;
        type = newType;
        step = Math.max(0, startStep);
        userVolume = savedUserVolume;
        gainMode = false;
        lastSet = -1;
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        output.startVibration();
        playChain(SoundStage.PRIMARY_TONE);
        applyLevel();
        escalating = step < lastStep();
        scheduleTick();
    }

    /**
     * Re-issues the repeating vibration after a screen change: HyperOS cancels an alarm vibration when the screen
     * turns off (RISK-25). No-op unless the engine rings.
     */
    void refreshVibration() {
        if (active) {
            output.startVibration();
        }
    }

    @Override
    public void switchType(AlarmType newType) {
        if (!active) {
            return;
        }
        if (snoozed != newType) {
            snoozed = null;
        }
        type = newType;
        stepListener.onStep(newType, step);
    }

    @Override
    public void keepAlive(AlarmType ringingType, int startStep, int savedUserVolume) {
        if (snoozeSilenced(ringingType)) {
            return;   // Q-71: the snoozed type stays silent until its Stop (or the fail-safe)
        }
        if (!active) {
            start(ringingType, startStep, savedUserVolume);
            return;
        }
        type = ringingType;
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    @Override
    public void silence(AlarmType snoozedType) {
        if (active && type == snoozedType) {
            snoozed = snoozedType;
            snoozedAtMs = elapsedMs.getAsLong();
            stopOutput();
            active = false;
            wakeLock.release();   // IR-89: nothing rings any more
        }
    }

    @Override
    public void stop() {
        stopOutput();
        active = false;
        snoozed = null;
        inCall = false;         // IR-90: nothing sticky across alarms
        focusLowered = false;
        wakeLock.release();
    }

    @Override
    public void restoreVolume(int saved) {
        volume.set(saved);
    }

    @Override
    public int currentVolume() {
        return volume.current();
    }

    @Override
    public int currentStep() {
        return step;
    }

    /**
     * Call start / end (F10): gain 30 % during a call, back to the escalation gain after it.
     *
     * @param call {@code true} while a call is active
     */
    void setInCall(boolean call) {
        inCall = call;
        if (active) {
            output.setGain(gain());
        }
    }

    /**
     * Permanent audio-focus loss outside a call (IR-82): 30 % without a re-post; {@code false} returns to the
     * escalation level.
     *
     * @param value {@code true} while lowered
     */
    /**
     * Tells whether the in-call gain applies.
     *
     * @return {@code true} while a call is reported
     */
    boolean inCall() {
        return inCall;
    }

    /**
     * Tells whether the focus-loss gain applies.
     *
     * @return {@code true} while lowered
     */
    boolean focusLowered() {
        return focusLowered;
    }

    void setFocusLowered(boolean value) {
        focusLowered = value;
        if (active) {
            output.setGain(gain());
        }
    }

    /**
     * Returns the snoozed type silenced in this process.
     *
     * @return the type, or {@code null}
     */
    AlarmType snoozed() {
        return snoozed;
    }

    /** The playing source failed at runtime: continue with the next stage of the chain (WF-02 step 3). */
    void onPlaybackError() {
        if (!active || stage == SoundStage.VIBRATION_ONLY) {
            return;
        }
        output.stopPlayback();
        SoundStage next = stage.next();
        logger.event(EventCode.ALARM_SOUND_FALLBACK, next.name());
        playChain(next);
        output.setGain(gain());
    }

    /**
     * Tells whether the engine rings (sound or vibration).
     *
     * @return {@code true} between start and stop / silence
     */
    boolean active() {
        return active;
    }

    /**
     * Returns the ringing type.
     *
     * @return the type, {@code null} before the first start
     */
    AlarmType type() {
        return type;
    }

    /**
     * Returns the sound stage in use.
     *
     * @return the stage
     */
    SoundStage stage() {
        return stage;
    }

    /**
     * Tells whether the escalation still runs.
     *
     * @return {@code false} after the maximum or a user volume change
     */
    boolean escalating() {
        return escalating;
    }

    /**
     * Tells whether the VR-12 gain fallback is in use.
     *
     * @return {@code true} when the stream volume could not be set
     */
    boolean gainMode() {
        return gainMode;
    }

    private void onTick() {
        tick = null;
        if (!active || !escalating) {
            return;
        }
        if (!gainMode && volume.current() != lastSet) {
            escalating = false;   // F8: a user volume change during ringing stops the escalation
            return;
        }
        step++;
        applyLevel();
        stepListener.onStep(type, step);
        escalating = step < lastStep();
        scheduleTick();
    }

    private boolean snoozeSilenced(AlarmType t) {
        if (snoozed == null) {
            return false;
        }
        if (snoozed != t) {
            snoozed = null;   // another type must ring at once (AL-6, F20)
            return false;
        }
        if (elapsedMs.getAsLong() - snoozedAtMs < SNOOZE_SILENCE_GUARD_MS) {
            return true;
        }
        snoozed = null;   // the IF-25 Stop never came: ring again (fail-safe)
        return false;
    }

    private void scheduleTick() {
        if (escalating) {
            tick = scheduler.schedule(this::onTick, EscalationSchedule.STEP_MS);
        }
    }

    private int lastStep() {
        if (gainMode) {
            return EscalationSchedule.fallbackLastStep();
        }
        return EscalationSchedule.lastStep(userVolume, volume.min(), volume.max());
    }

    private void applyLevel() {
        if (!gainMode) {
            int index = EscalationSchedule.level(userVolume, volume.min(), volume.max(), step);
            if (volume.set(index)) {
                lastSet = index;
            } else {
                gainMode = true;   // VR-12 fallback: player-gain escalation within the current stream volume
            }
        }
        output.setGain(gain());
    }

    private float gain() {
        float escalationGain = gainMode ? EscalationSchedule.fallbackGain(step) : 1f;
        return inCall || focusLowered ? Math.min(EscalationSchedule.IN_CALL_GAIN, escalationGain) : escalationGain;
    }

    private void playChain(SoundStage first) {
        SoundStage s = first;
        while (s != SoundStage.VIBRATION_ONLY && !output.play(s)) {
            s = s.next();
            logger.event(EventCode.ALARM_SOUND_FALLBACK, s.name());
        }
        stage = s;
    }

    private void stopOutput() {
        if (tick != null) {
            tick.cancel();
            tick = null;
        }
        escalating = false;
        output.stopPlayback();
        output.stopVibration();
    }
}
