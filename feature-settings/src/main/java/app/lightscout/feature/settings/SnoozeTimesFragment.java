package app.lightscout.feature.settings;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;

import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;
import app.lightscout.ui.core.EdgeToEdgeInsets;

import java.util.ArrayList;
import java.util.List;

/**
 * S-15 Snooze times: one row per alarm type with its snooze in minutes; a tap opens the numeric dialog of S-04 for that
 * value. The screen shows the thresholds and switches of S-04 nowhere: they stay there.
 */
public final class SnoozeTimesFragment extends Fragment implements SettingsViewModelOwner {

    private SettingsViewModel viewModel;
    private SoundRowList rows;

    public SnoozeTimesFragment() {
        super(R.layout.fragment_sound_screen);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = SettingsFragment.createViewModel(this, SettingsHost.dependencies(this));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(R.id.sound_toolbar);
        toolbar.setTitle(R.string.s15_title);
        setStandardUpDescription(toolbar);
        toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.sound_app_bar));
        NestedScrollView scroll = view.findViewById(R.id.sound_scroll);
        EdgeToEdgeInsets.applyScrollBottomInset(scroll, null);
        LinearLayout content = view.findViewById(R.id.sound_content);
        rows = new SoundRowList(content);
        viewModel.state().observe(getViewLifecycleOwner(), state -> render(state.settings(), state.loaded()));
    }

    @Override
    public void onDestroyView() {
        rows = null;
        super.onDestroyView();
    }

    @Override
    public SettingsViewModel viewModel() {
        return viewModel;
    }

    @SuppressLint("PrivateResource")
    private void setStandardUpDescription(MaterialToolbar toolbar) {
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
    }

    private void render(Settings settings, boolean loaded) {
        if (rows == null) {
            return;
        }
        List<SoundRowList.Spec> specs = new ArrayList<>();
        for (SnoozeTimes.Row row : SnoozeTimes.ROWS) {
            String value = getString(R.string.s04_value_min, SnoozeTimes.minutes(settings, row));
            String description = getString(R.string.s04_row_value_a11y, getString(snoozeLabel(row.key())), value);
            specs.add(SoundRowList.Spec.row("snooze:" + row.key().name(), getString(SoundText.alarmName(row.type())),
                    value, description, loaded, null, () -> openDialog(row.key())));
        }
        rows.bind(specs);
    }

    private void openDialog(SettingKey key) {
        if (!viewModel.snapshot().loaded()) {
            return;
        }
        if (getChildFragmentManager().findFragmentByTag(SettingsValueDialogFragment.TAG) == null) {
            SettingsValueDialogFragment.newInstance(key)
                    .show(getChildFragmentManager(), SettingsValueDialogFragment.TAG);
        }
    }

    private static int snoozeLabel(SettingKey key) {
        return switch (key) {
            case URGENT_LOW_SNOOZE -> R.string.s04_urgent_low_snooze;
            case LOW_SNOOZE -> R.string.s04_low_snooze;
            case NO_DATA_SNOOZE -> R.string.s04_no_data_snooze;
            case HIGH_SNOOZE -> R.string.s04_high_snooze;
            default -> throw new IllegalArgumentException("Not a snooze setting: " + key);
        };
    }
}
