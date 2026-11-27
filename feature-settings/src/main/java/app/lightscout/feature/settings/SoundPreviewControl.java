package app.lightscout.feature.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview;

import java.util.Objects;

/**
 * What the sound screens know about the preview (IF-71): which alarm type plays now, and a problem to show once.
 * Events come on the main thread.
 */
final class SoundPreviewControl {

    /** Why a preview did not play. */
    enum Problem {
        /** The sound could not be played. */
        FAILED,
        /** An alarm is ringing, so nothing was played. */
        ALARM_RINGING
    }

    private final SoundPreview preview;
    private final MutableLiveData<AlarmType> playing = new MutableLiveData<>();
    private final MutableLiveData<Problem> problem = new MutableLiveData<>();
    private volatile AlarmType current;
    private final SoundPreview.Listener listener = this::onEvent;

    SoundPreviewControl(SoundPreview preview) {
        this.preview = Objects.requireNonNull(preview, "preview");
    }

    LiveData<AlarmType> playing() {
        return playing;
    }

    LiveData<Problem> problem() {
        return problem;
    }

    void clearProblem() {
        problem.postValue(null);
    }

    /** The play button: starts the preview of a type, or stops it when it already plays. */
    void toggle(AlarmType type) {
        preview.play(type, listener);
    }

    /** After a change of the choice: plays the new sound from the start, even when a preview was running. */
    void restart(AlarmType type) {
        preview.stop();
        preview.play(type, listener);
    }

    /** Leaving a screen. */
    void stop() {
        preview.stop();
        current = null;
        playing.postValue(null);
    }

    private void onEvent(AlarmType type, SoundPreview.Event event) {
        switch (event) {
            case STARTED:
                current = type;
                playing.postValue(type);
                break;
            case STOPPED:
                release(type);
                break;
            case FAILED:
                release(type);
                problem.postValue(Problem.FAILED);
                break;
            case ALARM_RINGING:
                problem.postValue(Problem.ALARM_RINGING);
                break;
            default:
                break;
        }
    }

    private void release(AlarmType type) {
        if (current == type) {
            current = null;
            playing.postValue(null);
        }
    }
}
