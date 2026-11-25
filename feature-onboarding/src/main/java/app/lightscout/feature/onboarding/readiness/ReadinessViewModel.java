package app.lightscout.feature.onboarding.readiness;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.feature.onboarding.OneShot;

/**
 * S-07 ViewModel: exposes {@link ReadinessModel} as LiveData (CP-06 §8.4). Fragment-scoped; statuses are
 * recomputed on every resume, so nothing goes into saved state (CP-14 §7.4.2 Lifecycle).
 */
public final class ReadinessViewModel extends ViewModel implements ReadinessModel.Listener {

    private final ReadinessModel model;
    private final MutableLiveData<ReadinessUiState> state = new MutableLiveData<>();
    private final MutableLiveData<OneShot<ReadinessModel.Command>> commands = new MutableLiveData<>();

    /**
     * Creates the ViewModel around its model.
     *
     * @param model the S-07 model
     */
    public ReadinessViewModel(ReadinessModel model) {
        this.model = model;
        model.setListener(this);
    }

    /**
     * Returns the screen state.
     *
     * @return LiveData of the state
     */
    public LiveData<ReadinessUiState> state() {
        return state;
    }

    /**
     * Returns the one-shot commands (open a page, request a permission, navigate).
     *
     * @return LiveData of the commands
     */
    public LiveData<OneShot<ReadinessModel.Command>> commands() {
        return commands;
    }

    /**
     * Returns the model for user actions.
     *
     * @return the model
     */
    public ReadinessModel model() {
        return model;
    }

    @Override
    public void onState(ReadinessUiState s) {
        state.setValue(s);
    }

    @Override
    public void onCommand(ReadinessModel.Command command) {
        commands.setValue(new OneShot<>(command));
    }

    @Override
    protected void onCleared() {
        model.setListener(null);
    }
}
