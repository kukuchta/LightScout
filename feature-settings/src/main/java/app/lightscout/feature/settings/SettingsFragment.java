package app.lightscout.feature.settings;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.ThemeMode;
import app.lightscout.ui.core.AppInfo;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.NavResults;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.domain.update.UpdateOffer;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.sound.AlarmSoundService;
import app.lightscout.usecases.update.UpdateService;
import app.lightscout.platform.ReadinessChecker;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.color.MaterialColors;

import java.util.List;
import java.util.concurrent.Executor;

/** S-04 Settings surface. Rows only call use cases and IF-29; persistence is owned by the use cases. */
public final class SettingsFragment extends Fragment {

    private SettingsViewModel viewModel;
    private LinearLayout content;
    private boolean navigating;
    /** DCR-49: the update check choice and the newer version it found; read off the main thread, shown on it. */
    private UpdateService updates;
    private Executor io;
    private volatile boolean updateLoaded;
    private volatile boolean updateEnabled = true;
    private volatile UpdateOffer updateOffer;
    /** DCR-51: the alarm types that do not use the classic tone, for the "Alarm sounds" row; read off the main thread. */
    private Dependencies dependencies;
    private volatile List<AlarmType> customSounds = List.of();

    public SettingsFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dependencies = SettingsHost.dependencies(this);
        SettingsService settings = dependencies.get(SettingsService.class);
        AlarmActions alarmActions = dependencies.get(AlarmActions.class);
        ConnectionService connection = dependencies.get(ConnectionService.class);
        ReadinessChecker readiness = dependencies.get(ReadinessChecker.class);
        Executor ioExecutor = dependencies.ioExecutor();
        io = ioExecutor;
        updates = dependencies.get(UpdateService.class);
        AppInfo appInfo = dependencies.appInfo();
        viewModel = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass,
                                                   @NonNull CreationExtras extras) {
                if (!modelClass.isAssignableFrom(SettingsViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel " + modelClass.getName());
                }
                return modelClass.cast(new SettingsViewModel(settings, alarmActions, connection,
                        readiness, ioExecutor, appInfo));
            }
        }).get(SettingsViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(R.id.s04_toolbar);
        setStandardUpDescription(toolbar);
        toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.s04_app_bar));
        NestedScrollView scroll = view.findViewById(R.id.s04_scroll);
        EdgeToEdgeInsets.applyScrollBottomInset(scroll, null);
        content = view.findViewById(R.id.s04_content);
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        NavResults.observe(this, R.id.settingsFragment, this::onResult);
    }

    /** S-06 opened from here (connection edit) returns {@code CONNECTION_SAVED} (A-S06-3): confirm it with the Snackbar. */
    private void onResult(UiMessage message) {
        if (message == UiMessage.CONNECTION_SAVED && getView() != null) {
            Snackbar.make(getView(), app.lightscout.ui.core.R.string.s06_snackbar_saved, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        navigating = false;
        viewModel.refreshReadiness();
        io.execute(this::readUpdateState);
        io.execute(this::readSoundSummary);
    }

    /** DCR-51: reads which alarm types use a chosen sound (stored state, no network), then redraws. */
    private void readSoundSummary() {
        try {
            dependencies.get(AlarmSoundService.class).loadLibrary(library -> {
                customSounds = SoundLabels.customisedTypes(library);
                FragmentActivity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        if (getView() != null) {
                            render(viewModel.snapshot());
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            // the row keeps "LightScout classic"; the next resume reads again
        }
    }

    /** DCR-49: reads the check choice and the newer version found earlier (stored state, no network), then redraws. */
    private void readUpdateState() {
        try {
            updateEnabled = updates.isEnabled();
            updateOffer = updates.available().orElse(null);
            updateLoaded = true;
        } catch (RuntimeException e) {
            return;   // the rows keep what they show; the next resume reads again
        }
        FragmentActivity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (getView() != null) {
                    render(viewModel.snapshot());
                }
            });
        }
    }

    private void onUpdateCheckChanged(boolean enabled) {
        updateEnabled = enabled;
        io.execute(() -> {
            try {
                updates.setEnabled(enabled);
            } catch (RuntimeException e) {
                // the read below shows the value that is really stored
            }
            readUpdateState();
        });
    }

    private void openRelease(UpdateOffer offer) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(offer.pageUrl()))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            View root = getView();
            if (root != null) {
                Snackbar.make(root, R.string.s04_update_open_failed, Snackbar.LENGTH_LONG).show();
            }
        }
    }

    SettingsViewModel viewModel() {
        return viewModel;
    }

    @SuppressLint("PrivateResource")
    private void setStandardUpDescription(MaterialToolbar toolbar) {
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
    }

    private void render(SettingsUiState state) {
        if (state == null || content == null) {
            return;
        }
        Settings settings = state.settings();
        content.removeAllViews();

        addHeader(R.string.s04_header_alarm_settings);
        addSwitchRow(R.string.s04_alarms, null, settings.alarmsEnabled(),
                getString(R.string.s04_row_value_a11y, getString(R.string.s04_alarms),
                        stateOf(settings.alarmsEnabled())),
                enabled -> viewModel.setAlarmsEnabled(enabled), state.loaded());
        addSwitchRow(R.string.s04_low_alarm, getString(R.string.s04_value_mgdl, settings.lowMgdl()),
                settings.lowEnabled(), getString(R.string.s04_row_a11y, getString(R.string.s04_low_alarm),
                        stateOf(settings.lowEnabled()), settings.lowMgdl()),
                enabled -> viewModel.setAlarmTypeEnabled(AlarmType.LOW, enabled), state.loaded(),
                SettingKey.LOW_THRESHOLD);
        String urgentSupport = getString(R.string.s04_urgent_low_support,
                getString(R.string.s04_value_mgdl, settings.urgentLowMgdl()),
                getString(R.string.sep_dot), getString(R.string.s04_always_on));
        addValueRow(R.string.s04_urgent_low_alarm, urgentSupport, SettingKey.URGENT_LOW_THRESHOLD, false);
        addSwitchRow(R.string.s04_high_alarm, getString(R.string.s04_value_mgdl, settings.highMgdl()),
                settings.highEnabled(), getString(R.string.s04_row_a11y, getString(R.string.s04_high_alarm),
                        stateOf(settings.highEnabled()), settings.highMgdl()),
                enabled -> viewModel.setAlarmTypeEnabled(AlarmType.HIGH, enabled), state.loaded(),
                SettingKey.HIGH_THRESHOLD);
        addValueRow(R.string.s04_no_data_after,
                getString(R.string.s04_value_min, settings.noDataMinutes()), SettingKey.NO_DATA_MINUTES,
                state.loaded());
        addValueRow(R.string.s04_low_snooze,
                getString(R.string.s04_value_min, settings.lowSnoozeMin()), SettingKey.LOW_SNOOZE, state.loaded());
        addValueRow(R.string.s04_urgent_low_snooze,
                getString(R.string.s04_value_min, settings.urgentLowSnoozeMin()),
                SettingKey.URGENT_LOW_SNOOZE, state.loaded());
        addValueRow(R.string.s04_high_snooze,
                getString(R.string.s04_value_min, settings.highSnoozeMin()), SettingKey.HIGH_SNOOZE,
                state.loaded());
        addValueRow(R.string.s04_no_data_snooze,
                getString(R.string.s04_value_min, settings.noDataSnoozeMin()), SettingKey.NO_DATA_SNOOZE,
                state.loaded());
        addLinkRow(R.string.s04_alarm_sounds, SoundText.summary(requireContext(), customSounds), false, true,
                this::navigateToAlarmSounds);

        addHeader(R.string.s04_header_consumables);
        addValueRow(R.string.s04_site_lifetime,
                getString(R.string.s04_value_days, settings.siteLifetimeDays()), SettingKey.SITE_LIFETIME,
                state.loaded());
        addValueRow(R.string.s04_sensor_lifetime,
                getString(R.string.s04_value_days, settings.sensorLifetimeDays()), SettingKey.SENSOR_LIFETIME,
                state.loaded());

        addHeader(R.string.s04_header_statistics);
        addValueRow(R.string.s04_target_range,
                getString(R.string.s04_value_range, settings.targetRange().lowMgdl(),
                        settings.targetRange().highMgdl()), SettingKey.TARGET_RANGE, state.loaded());
        addSwitchRow(R.string.s04_statistics_card_on_home, null, settings.statisticsCardOnHome(),
                getString(R.string.s04_row_value_a11y, getString(R.string.s04_statistics_card_on_home),
                        stateOf(settings.statisticsCardOnHome())),
                shown -> viewModel.setStatisticsCardOnHome(shown), state.loaded());

        addHeader(R.string.s04_header_appearance);
        String themeValue = getString(themeLabel(settings.themeMode()));
        addRow(R.string.s04_theme_mode, themeValue,
                state.loaded() ? getString(R.string.s04_row_value_a11y, getString(R.string.s04_theme_mode), themeValue)
                        : null, state.loaded(), false, null, null, () -> openThemeDialog(settings.themeMode()));

        addHeader(R.string.s04_header_connection);
        addLinkRow(R.string.s04_nightscout_connection, state.host(), false, true,
                () -> navigateToConnection());
        int missing = state.missingRequiredCount();
        String readiness = missing == 0 ? getString(R.string.s04_readiness_all_set)
                : getResources().getQuantityString(R.plurals.s04_readiness_items, missing, missing);
        addLinkRow(R.string.s04_alarm_readiness, readiness, missing > 0, true,
                () -> navigateToReadiness());

        addHeader(R.string.s04_header_about);
        addSwitchRow(R.string.s04_update_check, getString(R.string.s04_update_check_support), updateEnabled,
                getString(R.string.s04_row_value_a11y, getString(R.string.s04_update_check),
                        stateOf(updateEnabled)),
                this::onUpdateCheckChanged, updateLoaded);
        UpdateOffer offer = updateEnabled ? updateOffer : null;
        if (offer != null) {
            addLinkRow(R.string.s04_update_available, getString(R.string.s04_version, offer.version().toString()),
                    false, true, () -> openRelease(offer));
        }
        addStaticRow(getString(R.string.s04_version, viewModel.appInfo().versionName()));
    }

    private void addHeader(int label) {
        TextView header = new TextView(requireContext());
        header.setText(label);
        header.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        header.setTextColor(MaterialColors.getColor(header, androidx.appcompat.R.attr.colorPrimary));
        header.setAccessibilityHeading(true);
        header.setPadding(0, dp(20), 0, dp(8));
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addValueRow(int label, String value, SettingKey key, boolean enabled) {
        addRow(label, value, enabled ? getString(R.string.s04_row_value_a11y,
                getString(label), value) : null, enabled, false, null, null,
                () -> openDialog(key));
    }

    private void addLinkRow(int label, String value, boolean warning, boolean enabled, Runnable action) {
        View icon = warning ? warningIcon() : null;
        String accessibleText = value.isEmpty() ? getString(label)
                : getString(R.string.s04_row_value_a11y, getString(label), value);
        addRow(label, value, accessibleText, enabled,
                true, icon, null, action);
    }

    private void addStaticRow(String label) {
        addRow(label, null, label, true, false, null, null, null, null);
    }

    private void addSwitchRow(int label, @Nullable String value, boolean checked,
                              @Nullable String accessibleText, SwitchAction action, boolean enabled) {
        addSwitchRow(label, value, checked, accessibleText, action, enabled, null);
    }

    private void addSwitchRow(int label, @Nullable String value, boolean checked,
                              @Nullable String accessibleText, SwitchAction action, boolean enabled,
                              @Nullable SettingKey tapKey) {
        MaterialSwitch toggle = new MaterialSwitch(requireContext());
        toggle.setChecked(checked);
        toggle.setEnabled(enabled);
        toggle.setContentDescription(accessibleText == null
                ? getString(label) : accessibleText);
        toggle.setMinimumWidth(dp(48));
        toggle.setMinimumHeight(dp(48));
        toggle.setOnCheckedChangeListener((button, isChecked) -> action.onChanged(isChecked));
        View divider = verticalDivider();
        LinearLayout switchArea = new LinearLayout(requireContext());
        switchArea.setGravity(Gravity.CENTER);
        switchArea.setPadding(dp(8), 0, 0, 0);
        switchArea.addView(toggle, new LinearLayout.LayoutParams(dp(56), dp(56)));
        addRow(label, value, accessibleText, enabled, false, null, switchArea,
                tapKey == null ? null : () -> openDialog(tapKey), divider);
    }

    private void addRow(int label, @Nullable String value, @Nullable String accessibleText,
                        boolean enabled, boolean chevron, @Nullable View trailing,
                        @Nullable View switchArea, @Nullable Runnable action) {
        addRow(label, value, accessibleText, enabled, chevron, trailing, switchArea, action, null);
    }

    private void addRow(int label, @Nullable String value, @Nullable String accessibleText,
                        boolean enabled, boolean chevron, @Nullable View trailing,
                        @Nullable View switchArea, @Nullable Runnable action, @Nullable View switchDivider) {
        addRow(getString(label), value, accessibleText, enabled, chevron, trailing,
                switchArea, action, switchDivider);
    }

    private void addRow(String label, @Nullable String value, @Nullable String accessibleText,
                        boolean enabled, boolean chevron, @Nullable View trailing,
                        @Nullable View switchArea, @Nullable Runnable action, @Nullable View switchDivider) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(value == null ? 56 : 68));
        row.setPadding(0, dp(8), 0, dp(8));
        row.setEnabled(enabled);
        if (accessibleText != null) {
            row.setFocusable(true);
            row.setScreenReaderFocusable(true);
            row.setClickable(action != null && enabled);
        }
        if (action != null) {
            row.setOnClickListener(v -> {
                if (enabled) {
                    action.run();
                }
            });
        }
        if (accessibleText != null) {
            row.setContentDescription(accessibleText);
        }

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView title = new TextView(requireContext());
        title.setText(label);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        text.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (value != null && !value.isEmpty()) {
            TextView support = new TextView(requireContext());
            support.setText(value);
            support.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            support.setTextColor(MaterialColors.getColor(support,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            support.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            text.addView(support, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(text, textParams);

        if (trailing != null) {
            row.addView(trailing, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }
        if (switchDivider != null) {
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(36));
            dividerParams.setMarginStart(dp(8));
            row.addView(switchDivider, dividerParams);
        }
        if (switchArea != null) {
            row.addView(switchArea, new LinearLayout.LayoutParams(dp(64), dp(56)));
        } else if (chevron) {
            ImageView arrow = new ImageView(requireContext());
            arrow.setImageResource(R.drawable.ic_s04_chevron);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }
        content.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View separator = new View(requireContext());
        separator.setBackgroundColor(MaterialColors.getColor(row,
                com.google.android.material.R.attr.colorOutlineVariant));
        content.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private View verticalDivider() {
        View divider = new View(requireContext());
        divider.setBackgroundColor(MaterialColors.getColor(divider,
                com.google.android.material.R.attr.colorOutlineVariant));
        return divider;
    }

    private View warningIcon() {
        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_s04_warning);
        icon.setContentDescription(getString(R.string.s07_status_needs_attention));
        return icon;
    }

    private static int themeLabel(ThemeMode mode) {
        return switch (mode) {
            case SYSTEM -> R.string.s04_theme_system;
            case LIGHT -> R.string.s04_theme_light;
            case DARK -> R.string.s04_theme_dark;
        };
    }

    /** Light / dark / system choice; the activity is recreated once the choice is stored, so it reads it at start. */
    private void openThemeDialog(ThemeMode current) {
        ThemeMode[] modes = ThemeMode.values();
        // Own radio buttons: the stock single-choice indicator did not show the current choice (RD-1, EV-64).
        RadioGroup group = new RadioGroup(requireContext());
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(dp(16), dp(8), dp(16), dp(8));
        for (ThemeMode mode : modes) {
            MaterialRadioButton button = new MaterialRadioButton(requireContext());
            button.setId(View.generateViewId());
            button.setTag(mode);
            button.setText(themeLabel(mode));
            button.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            button.setMinHeight(dp(48));
            group.addView(button, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            button.setChecked(mode == current);
        }
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.s04_theme_mode)
                .setView(group)
                .setNegativeButton(R.string.s04_dialog_cancel, null)
                .create();
        group.setOnCheckedChangeListener((g, checkedId) -> {
            ThemeMode chosen = (ThemeMode) g.findViewById(checkedId).getTag();
            dialog.dismiss();
            if (chosen == current) {
                return;
            }
            viewModel.save(new SettingChange(SettingKey.THEME_MODE, chosen.code(), null), result -> {
                FragmentActivity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(activity::recreate);
                }
            });
        });
        dialog.show();
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

    private void navigateToConnection() {
        if (navigating) {
            return;
        }
        navigating = true;
        SettingsHost.navigator(this).openConnection(ConnectionMode.EDIT);
    }

    private void navigateToAlarmSounds() {
        if (navigating) {
            return;
        }
        navigating = true;
        NavHostFragment.findNavController(this).navigate(R.id.action_settings_to_alarmSounds);
    }

    private void navigateToReadiness() {
        if (navigating) {
            return;
        }
        navigating = true;
        SettingsHost.navigator(this).openReadiness(ReadinessMode.CHECK);
    }

    private String stateOf(boolean enabled) {
        return getString(enabled ? R.string.a11y_state_on : R.string.a11y_state_off);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @FunctionalInterface
    private interface SwitchAction {
        void onChanged(boolean enabled);
    }
}
