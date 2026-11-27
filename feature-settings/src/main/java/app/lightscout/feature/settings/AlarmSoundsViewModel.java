package app.lightscout.feature.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.usecases.sound.AlarmSoundService;

import java.util.Objects;

/** S-13 coordinator (DCR-51): the five choices and their play buttons. Blocking work stays in the service. */
final class AlarmSoundsViewModel extends ViewModel {

    private final AlarmSoundService service;
    private final SoundPreviewControl preview;
    private final MutableLiveData<SoundListState> state =
            new MutableLiveData<>(new SoundListState(SoundLibrary.EMPTY, false));
    private volatile boolean cleared;

    AlarmSoundsViewModel(AlarmSoundService service, SoundPreview preview) {
        this.service = Objects.requireNonNull(service, "service");
        this.preview = new SoundPreviewControl(preview);
        refresh();
    }

    LiveData<SoundListState> state() {
        return state;
    }

    LiveData<AlarmType> playing() {
        return preview.playing();
    }

    LiveData<SoundPreviewControl.Problem> problem() {
        return preview.problem();
    }

    void clearProblem() {
        preview.clearProblem();
    }

    /** Reads the library again; S-13 calls it on every resume because S-14 changes it. */
    void refresh() {
        service.loadLibrary(library -> {
            if (!cleared) {
                state.postValue(new SoundListState(library, true));
            }
        });
    }

    /** The play button of one row. */
    void togglePreview(AlarmType type) {
        preview.toggle(type);
    }

    /** Leaving the screen. */
    void stopPreview() {
        preview.stop();
    }

    @Override
    protected void onCleared() {
        cleared = true;
        preview.stop();
    }
}
