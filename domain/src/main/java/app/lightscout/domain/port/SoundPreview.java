package app.lightscout.domain.port;

import app.lightscout.domain.alarm.AlarmType;

/**
 * IF-71 (DCR-51): plays the sound chosen for one alarm type, once, so the user can judge it. The preview plays what
 * the alarm would play for that type (same resolver), at the current alarm volume, and never changes any volume.
 * It stops by itself after {@link #MAX_MILLIS}, on a second {@code play} of the same type, on {@link #stop()}, when
 * another app takes audio focus, and it never starts while an alarm rings.
 *
 * <p>All methods may be called from any thread. Events reach the listener on the executor the implementation was
 * given (the main thread in the app).
 */
public interface SoundPreview {

    /** Longest time one preview plays, in milliseconds (Q-8). */
    long MAX_MILLIS = 10_000L;

    /** What happened to the preview of one alarm type. */
    enum Event {
        /** The sound is playing. */
        STARTED,
        /** The sound ended, was stopped or was replaced. */
        STOPPED,
        /** The sound could not be opened or played. */
        FAILED,
        /** Nothing played because an alarm is ringing. */
        ALARM_RINGING
    }

    /** Receives the events of a preview. */
    interface Listener {

        /**
         * Called for each change.
         *
         * @param type  the alarm type whose sound it is
         * @param event what happened
         */
        void onEvent(AlarmType type, Event event);
    }

    /**
     * Starts the preview of {@code type}. A preview of another type that is playing is stopped first. When this
     * type is already playing, the call stops it instead.
     *
     * @param type     the alarm type
     * @param listener receives the events of this preview
     */
    void play(AlarmType type, Listener listener);

    /** Stops a playing preview; nothing happens when none plays. */
    void stop();
}
