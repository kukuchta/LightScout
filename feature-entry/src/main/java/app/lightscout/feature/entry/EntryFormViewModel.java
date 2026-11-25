package app.lightscout.feature.entry;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.domain.port.StatusListener;
import app.lightscout.usecases.StatusRepository;
import java.util.concurrent.Executor;

/**
 * CMP-73 {@code EntryFormViewModel} (CP-13): holds {@link EntryFormModel} across configuration changes (GIR-2) and
 * exposes its state and one-time events as {@code LiveData}; the model's saved state lives in the ViewModel's
 * {@code SavedStateHandle} (process death, CP-13 §7.7). {@code snapshot.online} comes from IF-21 and is moved to the
 * main thread. Main thread only.
 */
public final class EntryFormViewModel extends ViewModel implements EntryFormModel.Listener {

    private final EntryFormModel model;
    private final StatusRepository status;
    private final StatusListener statusListener;
    private final MutableLiveData<EntryFormUiState> state = new MutableLiveData<>();
    private final MutableLiveData<OneShot<EntryFormModel.Event>> events = new MutableLiveData<>();

    /**
     * Creates the ViewModel and starts the model.
     *
     * @param model  the form model
     * @param status IF-21 (online)
     * @param main   main-thread executor
     */
    public EntryFormViewModel(EntryFormModel model, StatusRepository status, Executor main) {
        this.model = model;
        this.status = status;
        this.statusListener = s -> main.execute(() -> model.setOnline(s.online()));
        model.setListener(this);
        status.addListener(statusListener);
        model.start();
    }

    /**
     * Render state.
     *
     * @return the state
     */
    public LiveData<EntryFormUiState> state() {
        return state;
    }

    /**
     * One-time events (finish, close, focus).
     *
     * @return the events
     */
    public LiveData<OneShot<EntryFormModel.Event>> events() {
        return events;
    }

    /**
     * The model (user actions).
     *
     * @return the model
     */
    public EntryFormModel model() {
        return model;
    }

    @Override
    public void onState(EntryFormUiState s) {
        state.setValue(s);
    }

    @Override
    public void onEvent(EntryFormModel.Event e) {
        events.setValue(new OneShot<>(e));
    }

    @Override
    protected void onCleared() {
        status.removeListener(statusListener);
        model.clear();
    }
}
