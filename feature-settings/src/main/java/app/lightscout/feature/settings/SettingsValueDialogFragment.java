package app.lightscout.feature.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** S-04 numeric edit dialog. DialogFragment view state preserves typed values across recreation. */
public final class SettingsValueDialogFragment extends DialogFragment {

    static final String TAG = "s04-value-dialog";
    private static final String ARG_KEY = "settingKey";

    private SettingKey key;
    private TextInputLayout firstLayout;
    private TextInputLayout secondLayout;
    private TextInputEditText firstInput;
    private TextInputEditText secondInput;
    private boolean saving;

    public SettingsValueDialogFragment() {
    }

    static SettingsValueDialogFragment newInstance(SettingKey key) {
        SettingsValueDialogFragment dialog = new SettingsValueDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_KEY, key.name());
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        key = SettingKey.valueOf(requireArguments().getString(ARG_KEY));
        Settings settings = parentViewModel().snapshot().settings();
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);

        firstLayout = new TextInputLayout(requireContext());
        firstLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        firstInput = input(R.id.s04_value_input, valueFor(settings));
        firstLayout.setHint(isTargetRange() ? R.string.s04_dialog_from : titleResource());
        firstLayout.setSuffixText(suffixResource() == 0 ? null : getString(suffixResource()));
        firstLayout.addView(firstInput, new TextInputLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(firstLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (isTargetRange()) {
            secondLayout = new TextInputLayout(requireContext());
            secondLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            secondInput = input(R.id.s04_to_input, settings.targetRange().highMgdl());
            secondLayout.setHint(R.string.s04_dialog_to);
            secondLayout.setSuffixText(getString(R.string.s04_dialog_suffix_mgdl));
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            secondParams.topMargin = dp(12);
            form.addView(secondLayout, secondParams);
            secondLayout.addView(secondInput, new TextInputLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        form.setPadding(dp(24), dp(8), dp(24), dp(4));
        firstInput.addTextChangedListener(validationWatcher);
        if (secondInput != null) {
            secondInput.addTextChangedListener(validationWatcher);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(titleResource());
        builder.setView(form);
        builder.setNegativeButton(R.string.s04_dialog_cancel, null);
        builder.setPositiveButton(R.string.s04_dialog_save, null);
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> save());
            updateValidation(null);
        });
        return dialog;
    }

    private final TextWatcher validationWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            updateValidation(null);
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    private void updateValidation(@Nullable ValidationResult supplied) {
        if (firstLayout == null || firstInput == null) {
            return;
        }
        ValidationResult result = supplied != null ? supplied
                : SettingsValidation.validate(parentViewModel().snapshot().settings(), key,
                        text(firstInput), secondInput == null ? null : text(secondInput));
        String error = SettingsValidation.message(requireContext(), result);
        firstLayout.setError(error);
        if (secondLayout != null) {
            secondLayout.setError(null);
        }
        if (getDialog() instanceof androidx.appcompat.app.AlertDialog alert) {
            View save = alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            if (save != null) {
                save.setEnabled(!saving && result.valid());
            }
        }
    }

    private void save() {
        if (saving) {
            return;
        }
        Settings settings = parentViewModel().snapshot().settings();
        ValidationResult validation = SettingsValidation.validate(settings, key, text(firstInput),
                secondInput == null ? null : text(secondInput));
        updateValidation(validation);
        if (!validation.valid()) {
            return;
        }
        SettingChange change = new SettingChange(key, Integer.parseInt(text(firstInput)),
                secondInput == null ? null : Integer.valueOf(text(secondInput)));
        saving = true;
        androidx.appcompat.app.AlertDialog alert = (androidx.appcompat.app.AlertDialog) requireDialog();
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        setCancelable(false);
        boolean accepted = parentViewModel().save(change, result -> {
            android.app.Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                if (result != null && result.valid()) {
                    dismiss();
                    return;
                }
                saving = false;
                setCancelable(true);
                alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                updateValidation(result);
            });
        });
        if (!accepted) {
            saving = false;
            setCancelable(true);
            alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            updateValidation(null);
        }
    }

    private SettingsViewModel parentViewModel() {
        return ((SettingsFragment) requireParentFragment()).viewModel();
    }

    private TextInputEditText input(int id, int value) {
        TextInputEditText editText = new TextInputEditText(requireContext());
        editText.setId(id);
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editText.setSingleLine(true);
        editText.setText(Integer.toString(value));
        editText.setSelection(editText.getText() == null ? 0 : editText.getText().length());
        return editText;
    }

    private int valueFor(Settings settings) {
        return switch (key) {
            case URGENT_LOW_THRESHOLD -> settings.urgentLowMgdl();
            case LOW_THRESHOLD -> settings.lowMgdl();
            case HIGH_THRESHOLD -> settings.highMgdl();
            case NO_DATA_MINUTES -> settings.noDataMinutes();
            case LOW_SNOOZE -> settings.lowSnoozeMin();
            case URGENT_LOW_SNOOZE -> settings.urgentLowSnoozeMin();
            case HIGH_SNOOZE -> settings.highSnoozeMin();
            case NO_DATA_SNOOZE -> settings.noDataSnoozeMin();
            case SITE_LIFETIME -> settings.siteLifetimeDays();
            case SENSOR_LIFETIME -> settings.sensorLifetimeDays();
            case TARGET_RANGE -> settings.targetRange().lowMgdl();
            case STATISTICS_CARD -> settings.statisticsCardOnHome() ? 1 : 0;
            case THEME_MODE -> settings.themeMode().code();
        };
    }

    private int titleResource() {
        return switch (key) {
            case URGENT_LOW_THRESHOLD -> R.string.s04_dialog_title_urgent_low_threshold;
            case LOW_THRESHOLD -> R.string.s04_dialog_title_low_threshold;
            case HIGH_THRESHOLD -> R.string.s04_dialog_title_high_threshold;
            case NO_DATA_MINUTES -> R.string.s04_no_data_after;
            case LOW_SNOOZE -> R.string.s04_low_snooze;
            case URGENT_LOW_SNOOZE -> R.string.s04_urgent_low_snooze;
            case HIGH_SNOOZE -> R.string.s04_high_snooze;
            case NO_DATA_SNOOZE -> R.string.s04_no_data_snooze;
            case SITE_LIFETIME -> R.string.s04_site_lifetime;
            case SENSOR_LIFETIME -> R.string.s04_sensor_lifetime;
            case TARGET_RANGE -> R.string.s04_target_range;
            case STATISTICS_CARD -> R.string.s04_statistics_card_on_home;   // a switch row, never a dialog
            case THEME_MODE -> R.string.s04_theme_mode;   // a choice dialog of the fragment, never this one
        };
    }

    private int suffixResource() {
        return switch (key) {
            case URGENT_LOW_THRESHOLD, LOW_THRESHOLD, HIGH_THRESHOLD, TARGET_RANGE ->
                    R.string.s04_dialog_suffix_mgdl;
            case NO_DATA_MINUTES, LOW_SNOOZE, URGENT_LOW_SNOOZE, HIGH_SNOOZE, NO_DATA_SNOOZE ->
                    R.string.s04_dialog_suffix_min;
            case SITE_LIFETIME, SENSOR_LIFETIME -> R.string.s04_dialog_suffix_days;
            case STATISTICS_CARD, THEME_MODE -> R.string.s04_dialog_suffix_days;   // unused: not numeric dialogs
        };
    }

    private boolean isTargetRange() {
        return key == SettingKey.TARGET_RANGE;
    }

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
