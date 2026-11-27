package app.lightscout.feature.settings;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.usecases.sound.AlarmSoundService;

/**
 * S-13 Alarm sounds (DCR-51): one row per alarm type with its current sound and a play button; a tap on the row opens
 * the picker (S-14) for that type. The screen reads the stored library on every resume, so a change made in S-14 shows
 * without any navigation result. The preview stops when the screen is left.
 */
public final class AlarmSoundsFragment extends Fragment {

    private AlarmSoundsViewModel viewModel;
    private LinearLayout content;
    private boolean navigating;

    public AlarmSoundsFragment() {
        super(R.layout.fragment_sound_screen);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        var dependencies = SettingsHost.dependencies(this);
        AlarmSoundService service = dependencies.get(AlarmSoundService.class);
        SoundPreview preview = dependencies.get(SoundPreview.class);
        viewModel = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass, @NonNull CreationExtras extras) {
                if (!modelClass.isAssignableFrom(AlarmSoundsViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel " + modelClass.getName());
                }
                return modelClass.cast(new AlarmSoundsViewModel(service, preview));
            }
        }).get(AlarmSoundsViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(R.id.sound_toolbar);
        toolbar.setTitle(R.string.s13_title);
        setStandardUpDescription(toolbar);
        toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.sound_app_bar));
        NestedScrollView scroll = view.findViewById(R.id.sound_scroll);
        EdgeToEdgeInsets.applyScrollBottomInset(scroll, null);
        content = view.findViewById(R.id.sound_content);
        viewModel.state().observe(getViewLifecycleOwner(), state -> render());
        viewModel.playing().observe(getViewLifecycleOwner(), playing -> render());
        viewModel.problem().observe(getViewLifecycleOwner(), this::onProblem);
    }

    @Override
    public void onResume() {
        super.onResume();
        navigating = false;
        viewModel.refresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        viewModel.stopPreview();
    }

    @SuppressLint("PrivateResource")
    private void setStandardUpDescription(MaterialToolbar toolbar) {
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
    }

    private void onProblem(@Nullable SoundPreviewControl.Problem problem) {
        if (problem == null) {
            return;
        }
        View root = getView();
        if (problem == SoundPreviewControl.Problem.FAILED && root != null) {
            Snackbar.make(root, R.string.sound_preview_failed, Snackbar.LENGTH_SHORT).show();
        }
        viewModel.clearProblem();   // an alarm that rings needs no message: the preview simply does not start
    }

    private void render() {
        SoundListState state = viewModel.state().getValue();
        if (state == null || content == null) {
            return;
        }
        AlarmType playing = viewModel.playing().getValue();
        SoundLibrary library = state.library();
        content.removeAllViews();
        for (AlarmType type : SoundLabels.ORDER) {
            addRow(library, type, type == playing, state.loaded());
        }
    }

    private void addRow(SoundLibrary library, AlarmType type, boolean playing, boolean loaded) {
        String alarm = getString(SoundText.alarmName(type));
        String sound = SoundText.soundName(requireContext(), library, library.choice().get(type));
        // the alarm is named too: five buttons that all say "Play LightScout classic" cannot be told apart
        String play = getString(playing ? R.string.sound_stop_cd : R.string.sound_play_cd,
                getString(R.string.s04_row_value_a11y, alarm, sound));
        View button = SoundScreenUi.iconButton(requireContext(),
                playing ? R.drawable.ic_sound_stop : R.drawable.ic_sound_play, play,
                () -> viewModel.togglePreview(type));
        button.setEnabled(loaded);
        SoundScreenUi.row(content, null, alarm, sound, getString(R.string.s04_row_value_a11y, alarm, sound),
                loaded, () -> openPicker(type), button);
    }

    private void openPicker(AlarmType type) {
        if (navigating) {
            return;
        }
        navigating = true;
        Bundle args = new Bundle();
        args.putString(AlarmSoundPickerFragment.ARG_ALARM_TYPE, type.name());
        NavHostFragment.findNavController(this).navigate(R.id.action_alarmSounds_to_picker, args);
    }
}
