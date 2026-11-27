package app.lightscout.platform.sound;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.SoundPreview;
import app.lightscout.domain.sound.PlayableSound;

/**
 * The rules of the sound preview (DCR-51, plan section 6), without any Android class so that they run in JVM tests.
 * One preview at a time: a second {@code play} of the playing type stops it, another type replaces it, it never
 * starts while an alarm rings, and it ends after {@link SoundPreview#MAX_MILLIS}, at the end of the sound, on a
 * playback error or on any loss of audio focus.
 *
 * <p>Not thread safe: every method runs on the one preview thread ({@link SoundPreviewPlayer}). Events go to the
 * listener of the play that started the sound.
 */
final class PreviewController {

    /** Opens and stops the sound. */
    interface Output {

        /**
         * Prepares the sound and starts it, once (no looping). A {@code false} answer means the output has released
         * everything it opened.
         *
         * @param sound what to play
         * @return {@code false} when it could not be opened or started
         */
        boolean start(PlayableSound sound);

        /** Stops and releases the sound. */
        void stop();
    }

    /** Audio focus; the preview asks for a transient gain only. */
    interface Focus {

        /**
         * Asks for focus. The answer is a hint: the preview plays either way (best effort, Q-8).
         *
         * @return {@code true} when granted
         */
        boolean request();

        /** Gives focus back. */
        void abandon();
    }

    /** Runs a task later on the preview thread. */
    interface Timer {

        /**
         * Schedules a task.
         *
         * @param task    what to run
         * @param delayMs delay in milliseconds
         * @return cancels the task
         */
        Runnable schedule(Runnable task, long delayMs);
    }

    private final AlarmSoundResolver sounds;
    private final BooleanSupplier alarmRinging;
    private final Output output;
    private final Focus focus;
    private final Timer timer;

    private AlarmType playingType;
    private SoundPreview.Listener playingListener;
    private Runnable cancelTimeout;
    private long generation;

    PreviewController(AlarmSoundResolver sounds, BooleanSupplier alarmRinging, Output output, Focus focus,
                      Timer timer) {
        this.sounds = Objects.requireNonNull(sounds, "sounds");
        this.alarmRinging = Objects.requireNonNull(alarmRinging, "alarmRinging");
        this.output = Objects.requireNonNull(output, "output");
        this.focus = Objects.requireNonNull(focus, "focus");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** Starts the preview of a type, stops it when it already plays, or replaces another one. */
    void play(AlarmType type, SoundPreview.Listener listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        if (playingType == type) {
            finish(SoundPreview.Event.STOPPED);
            return;
        }
        finish(SoundPreview.Event.STOPPED);
        if (isRinging()) {
            listener.onEvent(type, SoundPreview.Event.ALARM_RINGING);
            return;
        }
        PlayableSound sound;
        try {
            sound = sounds.playable(type);
        } catch (RuntimeException e) {
            listener.onEvent(type, SoundPreview.Event.FAILED);
            return;
        }
        requestFocus();
        if (!startOutput(sound)) {
            abandonFocus();
            listener.onEvent(type, SoundPreview.Event.FAILED);
            return;
        }
        playingType = type;
        playingListener = listener;
        long mine = ++generation;
        cancelTimeout = timer.schedule(() -> onTimeout(mine), SoundPreview.MAX_MILLIS);
        listener.onEvent(type, SoundPreview.Event.STARTED);
    }

    /** Stops a playing preview. */
    void stop() {
        finish(SoundPreview.Event.STOPPED);
    }

    /** The sound reached its end. */
    void onCompleted() {
        finish(SoundPreview.Event.STOPPED);
    }

    /** Another app (or an alarm) took audio focus, for any length of time. */
    void onFocusLost() {
        finish(SoundPreview.Event.STOPPED);
    }

    /** The player reported an error while playing. */
    void onError() {
        finish(SoundPreview.Event.FAILED);
    }

    private void onTimeout(long scheduledFor) {
        if (scheduledFor == generation) {   // a timer of an earlier preview that still fires is ignored
            finish(SoundPreview.Event.STOPPED);
        }
    }

    private boolean isRinging() {
        try {
            return alarmRinging.getAsBoolean();
        } catch (RuntimeException e) {
            return false;   // the probe is a guard, not a gate: a failing probe must not block the preview
        }
    }

    private boolean startOutput(PlayableSound sound) {
        try {
            return output.start(sound);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void stopOutput() {
        try {
            output.stop();
        } catch (RuntimeException e) {
            // the player is released or broken: nothing left to stop
        }
    }

    /** Ends the playing preview, if any, and tells its listener. */
    private void finish(SoundPreview.Event event) {
        if (playingType == null) {
            return;
        }
        AlarmType type = playingType;
        SoundPreview.Listener listener = playingListener;
        Runnable cancel = cancelTimeout;
        playingType = null;
        playingListener = null;
        cancelTimeout = null;
        generation++;
        if (cancel != null) {
            cancel.run();
        }
        stopOutput();
        abandonFocus();
        listener.onEvent(type, event);
    }

    private void requestFocus() {
        try {
            focus.request();   // a hint only (best effort)
        } catch (RuntimeException e) {
            // the preview plays without focus
        }
    }

    private void abandonFocus() {
        try {
            focus.abandon();
        } catch (RuntimeException e) {
            // nothing left to give back
        }
    }
}
