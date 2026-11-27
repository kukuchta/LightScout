package app.lightscout.feature.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview;
import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;
import app.lightscout.usecases.sound.AlarmSoundService;
import app.lightscout.usecases.sound.SoundAction;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * S-14 coordinator (DCR-51) for the sound of one alarm type. One change runs at a time: a tap while a phone sound is
 * being copied is ignored. A change that worked plays the sound once; the open removal (Undo) lives in the stored
 * library, so a recreated view shows it again, and leaving the screen closes it.
 */
final class AlarmSoundPickerViewModel extends ViewModel {

    private final AlarmSoundService service;
    private final AlarmType type;
    private final SoundPreviewControl preview;
    private final MutableLiveData<SoundPickerState> state =
            new MutableLiveData<>(new SoundPickerState(SoundLibrary.EMPTY, List.of(), false, null, false));
    private final MutableLiveData<PickerMessage> message = new MutableLiveData<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Object stateLock = new Object();
    private volatile SoundPickerState current = state.getValue();
    private volatile boolean cleared;
    /** {@code false} between the view's stop and its next start: a finished change then plays nothing. */
    private volatile boolean shown = true;

    AlarmSoundPickerViewModel(AlarmSoundService service, SoundPreview preview, AlarmType type) {
        this.service = Objects.requireNonNull(service, "service");
        this.preview = new SoundPreviewControl(preview);
        this.type = Objects.requireNonNull(type, "type");
        refresh();
    }

    AlarmType type() {
        return type;
    }

    LiveData<SoundPickerState> state() {
        return state;
    }

    LiveData<PickerMessage> message() {
        return message;
    }

    void clearMessage() {
        message.postValue(null);
    }

    LiveData<SoundPreviewControl.Problem> problem() {
        return preview.problem();
    }

    void clearProblem() {
        preview.clearProblem();
    }

    /** Reads the library and the phone's alarm sounds. */
    void refresh() {
        service.load(view -> update(old -> new SoundPickerState(view.library(), view.phoneSounds(), true,
                old.busyPhoneUri(), old.adding())));
    }

    /** A tap on classic, a bundled sound, an own sound or a phone sound that was copied before. */
    void select(SoundRef ref) {
        if (begin()) {
            service.select(type, ref, action -> done(action, true));
        }
    }

    /** A tap on a phone sound: copies it first (a progress state on its row), then selects it. */
    void selectPhone(PhoneSound sound) {
        if (begin()) {
            update(old -> new SoundPickerState(old.library(), old.phoneSounds(), old.loaded(), sound.uri(),
                    old.adding()));
            service.selectPhone(type, sound, action -> done(action, true));
        }
    }

    /** A file the user picked; it is copied, listed and selected for this type. */
    void addOwn(String documentUri, String displayName) {
        if (begin()) {
            update(old -> new SoundPickerState(old.library(), old.phoneSounds(), old.loaded(), null, true));
            service.addOwn(documentUri, displayName, type, action -> done(action, true));
        }
    }

    /** The trash button of an own sound: no dialog, the screen offers Undo. */
    void remove(String ownKey) {
        if (begin()) {
            service.remove(ownKey, action -> done(action, false));
        }
    }

    /**
     * The Undo action of the removal message. Not held back by a running copy: the service runs changes one after the
     * other, so Undo is applied in its turn instead of being lost.
     */
    void undo() {
        if (!cleared) {
            service.undoRemoval(action -> closeRemoval(action));
        }
    }

    /** The removal message went away without Undo; like {@link #undo()} it is not held back by a running copy. */
    void finalise() {
        if (!cleared) {
            service.finaliseRemoval(action -> closeRemoval(action));
        }
    }

    /** The view is in front again. */
    void onShown() {
        shown = true;
    }

    /**
     * Leaving the screen (the view stops): stops the preview, and a change that finishes later does not start it
     * again. The model itself does the same when it is cleared.
     */
    void stopPreview() {
        shown = false;
        preview.stop();
    }

    private boolean begin() {
        return !cleared && busy.compareAndSet(false, true);
    }

    private void done(SoundAction action, boolean playOnSuccess) {
        update(old -> new SoundPickerState(action.library(), old.phoneSounds(), true, null, false));
        busy.set(false);   // after the state: a tap in between must not have its progress state overwritten
        report(action, playOnSuccess);
    }

    /** Undo and finalise: the library changes, but a copy that still runs keeps its progress state. */
    private void closeRemoval(SoundAction action) {
        update(old -> new SoundPickerState(action.library(), old.phoneSounds(), true, old.busyPhoneUri(),
                old.adding()));
        report(action, false);
    }

    private void report(SoundAction action, boolean playOnSuccess) {
        if (cleared) {
            return;
        }
        if (action.outcome() == ImportOutcome.OK) {
            if (playOnSuccess && shown) {
                preview.restart(type);
            }
        } else {
            PickerMessage shown = messageFor(action.outcome());
            if (shown != null) {
                message.postValue(shown);
            }
        }
    }

    private static PickerMessage messageFor(ImportOutcome outcome) {
        switch (outcome) {
            case TOO_LARGE:
                return PickerMessage.TOO_LARGE;
            case NOT_PLAYABLE:
                return PickerMessage.NOT_PLAYABLE;
            case LIMIT_REACHED:
                return PickerMessage.LIMIT_REACHED;
            case CANCELLED:
            case OK:
                return null;
            default:
                return PickerMessage.CANNOT_READ;
        }
    }

    private void update(UnaryOperator<SoundPickerState> reducer) {
        synchronized (stateLock) {
            if (cleared) {
                return;
            }
            current = reducer.apply(current);
            state.postValue(current);
        }
    }

    @Override
    protected void onCleared() {
        cleared = true;
        preview.stop();
        // always: a removal still in flight is not in the state yet, and the service runs this after it; with no open
        // removal it changes nothing. Process death is covered by the start-up sweep.
        service.finaliseRemoval(action -> { });
    }
}
