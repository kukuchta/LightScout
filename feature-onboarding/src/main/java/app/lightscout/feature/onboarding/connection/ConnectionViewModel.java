package app.lightscout.feature.onboarding.connection;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.domain.port.StatusListener;
import app.lightscout.feature.onboarding.OneShot;
import app.lightscout.usecases.StatusRepository;

import java.util.concurrent.Executor;

/**
 * S-06 ViewModel, scoped to the {@code onboarding_graph} back-stack entry so that URL and token survive S-06 → S-05
 * → S-06 and S-07 → S-06 (M3-27, CP-00A R-38). Exposes {@link ConnectionModel} as LiveData and feeds it IF-21
 * {@code online}. The token lives only inside the model (memory) and is wiped in {@link #onCleared()} (SR-02).
 */
public final class ConnectionViewModel extends ViewModel implements ConnectionModel.Listener {

    private final ConnectionModel model;
    private final StatusRepository status;
    private final StatusListener statusListener;
    private final MutableLiveData<ConnectionUiState> state = new MutableLiveData<>();
    private final MutableLiveData<OneShot<ConnectionModel.Event>> events = new MutableLiveData<>();

    /**
     * Creates the ViewModel, starts the stored-connection load and listens to IF-21 connectivity.
     *
     * @param model  the S-06 model
     * @param status IF-21 (listener fires on {@code monitorExecutor})
     * @param main   main-thread executor
     */
    public ConnectionViewModel(ConnectionModel model, StatusRepository status, Executor main) {
        this.model = model;
        this.status = status;
        this.statusListener = s -> main.execute(() -> model.setOnline(s.online()));
        model.setListener(this);
        status.addListener(statusListener);
        model.start();
    }

    /**
     * Returns the screen state.
     *
     * @return LiveData of the state
     */
    public LiveData<ConnectionUiState> state() {
        return state;
    }

    /**
     * Returns one-time requests (open S-07, finish with result, leave).
     *
     * @return LiveData of the events
     */
    public LiveData<OneShot<ConnectionModel.Event>> events() {
        return events;
    }

    /**
     * Returns the model for user actions.
     *
     * @return the model
     */
    public ConnectionModel model() {
        return model;
    }

    @Override
    public void onState(ConnectionUiState s) {
        state.setValue(s);
    }

    @Override
    public void onEvent(ConnectionModel.Event e) {
        events.setValue(new OneShot<>(e));
    }

    @Override
    protected void onCleared() {
        status.removeListener(statusListener);
        model.clear();
    }
}
