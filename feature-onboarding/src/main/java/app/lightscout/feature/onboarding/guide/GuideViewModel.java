package app.lightscout.feature.onboarding.guide;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.feature.onboarding.OneShot;

/**
 * S-08 ViewModel: exposes {@link GuideModel} as LiveData (CP-06 §8.4). Fragment-scoped; the selected option lives in
 * the saved state.
 */
public final class GuideViewModel extends ViewModel implements GuideModel.Listener {

    private final GuideModel model;
    private final MutableLiveData<GuideUiState> state = new MutableLiveData<>();
    private final MutableLiveData<OneShot<GuideModel.Command>> commands = new MutableLiveData<>();

    /**
     * Creates the ViewModel around its model.
     *
     * @param model the S-08 model
     */
    public GuideViewModel(GuideModel model) {
        this.model = model;
        model.setListener(this);
    }

    /**
     * Returns the screen state.
     *
     * @return LiveData of the state
     */
    public LiveData<GuideUiState> state() {
        return state;
    }

    /**
     * Returns the one-shot commands (open a page, open the link, show the Snackbar).
     *
     * @return LiveData of the commands
     */
    public LiveData<OneShot<GuideModel.Command>> commands() {
        return commands;
    }

    /**
     * Returns the model for user actions.
     *
     * @return the model
     */
    public GuideModel model() {
        return model;
    }

    @Override
    public void onState(GuideUiState s) {
        state.setValue(s);
    }

    @Override
    public void onCommand(GuideModel.Command command) {
        commands.setValue(new OneShot<>(command));
    }

    @Override
    protected void onCleared() {
        model.setListener(null);
    }
}
