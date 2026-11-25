package app.lightscout.alarm;

/**
 * F10 / GIR-5 call detection and audio-focus handling while ringing, without {@code READ_PHONE_STATE} (CP-07 §8.3;
 * 🏛️ IR-82, Q-72, Q-73). Pure logic on the player thread; the SDK level, the audio mode and the focus request are
 * injected, so both API bands are covered by JVM tests (CP-00 §10 CR-04).
 *
 * <p><b>Call state comes only from the audio mode</b>: {@code IN_CALL} / {@code IN_COMMUNICATION} = call start,
 * {@code NORMAL} (any other mode) = call end. API 31+ (band B2+) uses a mode-change listener; API 29–30 (band B1)
 * polls the mode every 5 s while ringing. Only these transitions drive GIR-5 ({@link Listener#onCallStateChanged}):
 * 30 % gain at call start; at call end full gain and one CH-01 re-post with the FSI.
 *
 * <p><b>Audio focus only lowers the level</b> ({@link Listener#onFocusLowered}), never starts or ends a call:
 * <ul>
 *   <li>transient loss / {@code CAN_DUCK} → ignored (no gain change, no re-post);</li>
 *   <li>permanent {@code AUDIOFOCUS_LOSS} while the mode is not a call → lowered (30 %), no FSI re-post;</li>
 *   <li>focus is re-requested when the mode returns to {@code NORMAL} and on every keep-alive while it is not held
 *       ({@link #ensureFocus()}); a request result — granted, or refused with mode {@code NORMAL} (Q-72: not a call) —
 *       returns to the escalation level. Nothing is sticky.</li>
 * </ul>
 */
final class CallWatch {

    /** {@code AudioManager.MODE_IN_CALL}. */
    static final int MODE_IN_CALL = 2;
    /** {@code AudioManager.MODE_IN_COMMUNICATION} (VoIP). */
    static final int MODE_IN_COMMUNICATION = 3;
    /** {@code AudioManager.MODE_NORMAL}. */
    static final int MODE_NORMAL = 0;
    /** {@code AudioManager.AUDIOFOCUS_GAIN}. */
    static final int FOCUS_GAIN = 1;
    /** {@code AudioManager.AUDIOFOCUS_LOSS}. */
    static final int FOCUS_LOSS = -1;
    /** {@code AudioManager.AUDIOFOCUS_LOSS_TRANSIENT}. */
    static final int FOCUS_LOSS_TRANSIENT = -2;
    /** {@code AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}. */
    static final int FOCUS_LOSS_TRANSIENT_CAN_DUCK = -3;

    /** Polling interval on API 29–30 (F10). */
    static final long POLL_MS = 5_000L;

    /** First API level with {@code AudioManager.addOnModeChangedListener} (band B2). */
    static final int MODE_LISTENER_SDK = 31;

    /**
     * How the audio mode is observed on a given API level.
     */
    enum Detection {
        /** Mode-change listener (31+). */
        LISTENER,
        /** 5 s polling while ringing (29–30). */
        POLLING
    }

    /**
     * Audio-mode seam ({@code AudioManager}).
     */
    interface ModeSource {

        /**
         * Returns the current audio mode.
         *
         * @return the mode
         */
        int mode();

        /**
         * Registers the mode-change listener (31+ only).
         *
         * @param listener receives the new mode on the player thread
         */
        void addModeListener(ModeListener listener);

        /** Removes the mode-change listener (31+ only). */
        void removeModeListener();
    }

    /**
     * Receives audio-mode changes.
     */
    interface ModeListener {

        /**
         * Called with the new mode.
         *
         * @param mode the mode
         */
        void onMode(int mode);
    }

    /**
     * Audio-focus seam ({@code AUDIOFOCUS_GAIN_TRANSIENT} request with {@code USAGE_ALARM}); changes arrive through
     * {@link #onFocusChange(int)}.
     */
    interface FocusPort {

        /**
         * Requests audio focus.
         *
         * @return {@code true} when granted
         */
        boolean request();

        /** Abandons audio focus. */
        void abandon();
    }

    /**
     * Receives call and level changes.
     */
    interface Listener {

        /**
         * GIR-5: a call started ({@code true}) or ended ({@code false}) — from the audio mode only.
         *
         * @param inCall the new call state
         */
        void onCallStateChanged(boolean inCall);

        /**
         * The level is lowered to 30 % because of a permanent focus loss outside a call ({@code true}), or back to
         * the escalation level ({@code false}). Never a call start / end, never a re-post.
         *
         * @param lowered the new state
         */
        void onFocusLowered(boolean lowered);
    }

    private final int sdkInt;
    private final ModeSource modes;
    private final FocusPort focus;
    private final Scheduler scheduler;
    private final Listener listener;

    private boolean running;
    private boolean modeInCall;
    private boolean focusHeld;
    private boolean lowered;
    private Scheduler.Cancellable poll;

    /**
     * Creates the watch.
     *
     * @param sdkInt    {@code Build.VERSION.SDK_INT} (a fake in tests)
     * @param modes     audio-mode seam
     * @param focus     audio-focus seam
     * @param scheduler player-thread scheduler (polling)
     * @param listener  call / level receiver
     */
    CallWatch(int sdkInt, ModeSource modes, FocusPort focus, Scheduler scheduler, Listener listener) {
        this.sdkInt = sdkInt;
        this.modes = modes;
        this.focus = focus;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /**
     * Returns the detection used on an API level.
     *
     * @param sdkInt API level
     * @return listener on 31+, polling below
     */
    static Detection detectionFor(int sdkInt) {
        return sdkInt >= MODE_LISTENER_SDK ? Detection.LISTENER : Detection.POLLING;
    }

    /**
     * Tells whether an audio mode is a call.
     *
     * @param mode audio mode
     * @return {@code true} for {@code IN_CALL} and {@code IN_COMMUNICATION}
     */
    static boolean isCallMode(int mode) {
        return mode == MODE_IN_CALL || mode == MODE_IN_COMMUNICATION;
    }

    /**
     * Starts watching (ringing started): reports the <b>current</b> mode at once (a call still active yields
     * {@code onCallStateChanged(true)}, IR-90) and requests focus outside a call. Idempotent while running; after
     * {@link #stop()} every start reports afresh (the player's call / focus state was reset with it).
     */
    void start() {
        if (running) {
            return;
        }
        running = true;
        if (detectionFor(sdkInt) == Detection.LISTENER) {
            modes.addModeListener(this::onMode);
        } else {
            schedulePoll();
        }
        modeInCall = isCallMode(modes.mode());
        if (modeInCall) {
            listener.onCallStateChanged(true);
        }
        ensureFocus();
    }

    /** Stops watching (Snooze / stop), abandons focus and forgets all state. */
    void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (detectionFor(sdkInt) == Detection.LISTENER) {
            modes.removeModeListener();
        }
        if (poll != null) {
            poll.cancel();
            poll = null;
        }
        if (focusHeld) {
            focus.abandon();
        }
        modeInCall = false;
        focusHeld = false;
        lowered = false;
    }

    /**
     * Keep-alive and call end: re-requests focus while it is not held, never during a call (IR-91: a re-request
     * would take focus from the phone / VoIP app on every keep-alive; the mode-to-NORMAL path re-requests).
     */
    void ensureFocus() {
        if (!running || focusHeld || modeInCall) {
            return;
        }
        focusHeld = focus.request();
        setLowered(false);   // granted → escalation level; refused with mode NORMAL is not a call (Q-72)
    }

    /**
     * Audio-focus change of the alarm player.
     *
     * @param focusChange the {@code AudioManager.AUDIOFOCUS_*} value
     */
    void onFocusChange(int focusChange) {
        if (!running) {
            return;
        }
        if (focusChange == FOCUS_GAIN) {
            focusHeld = true;
            setLowered(false);
        } else if (focusChange == FOCUS_LOSS) {
            focusHeld = false;
            if (!modeInCall) {
                setLowered(true);
            }
        }
        // transient loss / CAN_DUCK: ignored (IR-82, Q-73)
    }

    /**
     * Tells whether a call is active (audio mode).
     *
     * @return {@code true} while the mode is a call
     */
    boolean inCall() {
        return modeInCall;
    }

    /**
     * Tells whether the level is lowered by a permanent focus loss.
     *
     * @return {@code true} while lowered
     */
    boolean lowered() {
        return lowered;
    }

    /**
     * Tells whether audio focus is held.
     *
     * @return {@code true} after a grant until a permanent loss
     */
    boolean focusHeld() {
        return focusHeld;
    }

    /**
     * Tells whether the watch runs.
     *
     * @return {@code true} while ringing
     */
    boolean running() {
        return running;
    }

    private void onMode(int mode) {
        if (!running) {
            return;
        }
        boolean call = isCallMode(mode);
        if (call == modeInCall) {
            return;
        }
        modeInCall = call;
        if (call) {
            listener.onCallStateChanged(true);
        } else {
            ensureFocus();
            listener.onCallStateChanged(false);
        }
    }

    private void setLowered(boolean value) {
        if (lowered != value) {
            lowered = value;
            listener.onFocusLowered(value);
        }
    }

    private void schedulePoll() {
        poll = scheduler.schedule(() -> {
            if (!running) {
                return;
            }
            onMode(modes.mode());
            schedulePoll();
        }, POLL_MS);
    }
}
