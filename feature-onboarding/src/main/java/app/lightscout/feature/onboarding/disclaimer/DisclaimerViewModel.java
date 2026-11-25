package app.lightscout.feature.onboarding.disclaimer;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.feature.onboarding.OneShot;

/**
 * S-05 ViewModel: exposes {@link DisclaimerModel} as LiveData (CP-06 §8.4). Fragment-scoped; the checkbox lives
 * in the saved state.
 */
public final class DisclaimerViewModel extends ViewModel implements DisclaimerModel.Listener {

    private final DisclaimerModel model;
    private final MutableLiveData<DisclaimerUiState> state = new MutableLiveData<>();
    private final MutableLiveData<OneShot<Boolean>> accepted = new MutableLiveData<>();

    /**
     * Creates the ViewModel around its model.
     *
     * @param model the S-05 model
     */
    public DisclaimerViewModel(DisclaimerModel model) {
        this.model = model;
        model.setListener(this);
    }

    /**
     * Returns the screen state.
     *
     * @return LiveData of the state
     */
    public LiveData<DisclaimerUiState> state() {
        return state;
    }

    /**
     * Returns the one-shot „acceptance stored → open S-06” event.
     *
     * @return LiveData of the event
     */
    public LiveData<OneShot<Boolean>> accepted() {
        return accepted;
    }

    /**
     * Returns the model for user actions.
     *
     * @return the model
     */
    public DisclaimerModel model() {
        return model;
    }

    @Override
    public void onState(DisclaimerUiState s) {
        state.setValue(s);
    }

    @Override
    public void onAccepted() {
        accepted.setValue(new OneShot<>(Boolean.TRUE));
    }

    @Override
    protected void onCleared() {
        model.setListener(null);
    }
}
