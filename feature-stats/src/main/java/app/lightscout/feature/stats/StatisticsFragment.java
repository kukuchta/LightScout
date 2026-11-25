package app.lightscout.feature.stats;

import android.os.Bundle;
import android.annotation.SuppressLint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.statistics.PeriodKind;
import app.lightscout.domain.statistics.Statistics;
import app.lightscout.feature.stats.StatisticsUiState.Screen;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.Formatters;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.Executor;

/** S-03 Statistics surface; the view only formats and displays IF-04 results. */
public final class StatisticsFragment extends Fragment {

    private StatisticsViewModel viewModel;
    private Clock clock;
    private MaterialButtonToggleGroup periodGroup;
    private TextView periodLabel;
    private View offlineLine;
    private View coverageLine;
    private TextView coverageText;
    private View loading;
    private View errorState;
    private TextView errorText;
    private View empty;
    private View content;
    private DistributionBarView distributionBar;
    private LinearLayout legend;
    private View resultFrame;
    private LinearLayout metrics;

    public StatisticsFragment() {
        super(app.lightscout.feature.stats.R.layout.fragment_statistics);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        var dependencies = StatisticsHost.dependencies(this);
        clock = dependencies.get(Clock.class);
        HistoryLoader history = dependencies.get(HistoryLoader.class);
        SettingsService settings = dependencies.get(SettingsService.class);
        StatusRepository status = dependencies.get(StatusRepository.class);
        Executor ioExecutor = dependencies.ioExecutor();
        viewModel = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass,
                                                   @NonNull CreationExtras extras) {
                if (!modelClass.isAssignableFrom(StatisticsViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel " + modelClass.getName());
                }
                return modelClass.cast(new StatisticsViewModel(clock, history, settings, status, ioExecutor));
            }
        }).get(StatisticsViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(app.lightscout.feature.stats.R.id.s03_toolbar);
        setStandardUpDescription(toolbar);
        toolbar.setNavigationOnClickListener(v -> NavHostFragment.findNavController(this).navigateUp());
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(app.lightscout.feature.stats.R.id.s03_app_bar));
        NestedScrollView scroll = view.findViewById(app.lightscout.feature.stats.R.id.s03_scroll);
        EdgeToEdgeInsets.applyScrollBottomInset(scroll, null);

        periodGroup = view.findViewById(app.lightscout.feature.stats.R.id.s03_period_group);
        periodLabel = view.findViewById(app.lightscout.feature.stats.R.id.s03_period_label);
        offlineLine = view.findViewById(app.lightscout.feature.stats.R.id.s03_offline_cache);
        coverageLine = view.findViewById(app.lightscout.feature.stats.R.id.s03_coverage);
        coverageText = view.findViewById(app.lightscout.feature.stats.R.id.s03_coverage_text);
        loading = view.findViewById(app.lightscout.feature.stats.R.id.s03_loading);
        errorState = view.findViewById(app.lightscout.feature.stats.R.id.s03_error_state);
        errorText = view.findViewById(app.lightscout.feature.stats.R.id.s03_error_text);
        empty = view.findViewById(app.lightscout.feature.stats.R.id.s03_empty);
        content = view.findViewById(app.lightscout.feature.stats.R.id.s03_content);
        distributionBar = view.findViewById(app.lightscout.feature.stats.R.id.s03_distribution_bar);
        legend = view.findViewById(app.lightscout.feature.stats.R.id.s03_legend);
        resultFrame = view.findViewById(app.lightscout.feature.stats.R.id.s03_result_frame);
        metrics = view.findViewById(app.lightscout.feature.stats.R.id.s03_metrics);

        setPeriodButtonText(view);
        periodGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                viewModel.select(periodFor(checkedId));
            }
        });
        view.findViewById(app.lightscout.feature.stats.R.id.s03_retry)
                .setOnClickListener(v -> viewModel.retry());
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
    }

    @SuppressLint("PrivateResource")
    private void setStandardUpDescription(MaterialToolbar toolbar) {
        toolbar.setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description);
    }

    private void setPeriodButtonText(View root) {
        int[][] labels = {
                {app.lightscout.feature.stats.R.id.s03_period_yesterday,
                        app.lightscout.feature.stats.R.string.s03_period_yesterday,
                        app.lightscout.feature.stats.R.string.s03_period_yesterday_short},
                {app.lightscout.feature.stats.R.id.s03_period_7_days,
                        app.lightscout.feature.stats.R.string.s03_period_7_days,
                        app.lightscout.feature.stats.R.string.s03_period_7_days_short},
                {app.lightscout.feature.stats.R.id.s03_period_14_days,
                        app.lightscout.feature.stats.R.string.s03_period_14_days,
                        app.lightscout.feature.stats.R.string.s03_period_14_days_short},
                {app.lightscout.feature.stats.R.id.s03_period_30_days,
                        app.lightscout.feature.stats.R.string.s03_period_30_days,
                        app.lightscout.feature.stats.R.string.s03_period_30_days_short}
        };
        boolean largeFont = getResources().getConfiguration().fontScale >= 1.3f;
        for (int[] label : labels) {
            setButton(root, label[0], label[1], largeFont ? label[2] : label[1]);
        }
        periodGroup.post(() -> {
            for (int[] label : labels) {
                MaterialButton button = root.findViewById(label[0]);
                int textWidth = Math.round(button.getPaint().measureText(getString(label[1])));
                int availableWidth = button.getWidth() - button.getPaddingLeft() - button.getPaddingRight()
                        - dp(8);
                if (textWidth > availableWidth) {
                    setButton(root, label[0], label[1], label[2]);
                }
            }
        });
    }

    private void setButton(View root, int id, int fullLabel, int visibleLabel) {
        MaterialButton button = root.findViewById(id);
        button.setText(visibleLabel);
        button.setContentDescription(getString(fullLabel));
        button.setSingleLine(true);
        button.setEllipsize(null);
    }

    private void render(StatisticsUiState state) {
        if (state == null || getView() == null) {
            return;
        }
        int checkedId = buttonFor(state.selectedPeriod());
        if (periodGroup.getCheckedButtonId() != checkedId) {
            periodGroup.check(checkedId);
        }
        Period period = state.period();
        periodLabel.setText(Formatters.periodLabel(requireContext(), period, clock.zone()));

        boolean hasResult = state.screen() == Screen.CONTENT || state.screen() == Screen.PARTIAL
                || state.screen() == Screen.EMPTY;
        boolean hasStatistics = state.screen() == Screen.CONTENT || state.screen() == Screen.PARTIAL;
        offlineLine.setVisibility(hasResult && state.offlineFromCache() ? View.VISIBLE : View.GONE);
        boolean partial = state.screen() == Screen.PARTIAL;
        coverageLine.setVisibility(partial ? View.VISIBLE : View.GONE);
        if (partial) {
            coverageText.setText(getString(app.lightscout.feature.stats.R.string.s03_coverage_partial,
                    roundPercent(state.statistics().coverage() * 100d)));
        }
        loading.setVisibility(state.screen() == Screen.LOADING ? View.VISIBLE : View.GONE);
        boolean error = state.screen() == Screen.ERROR || state.screen() == Screen.OFFLINE;
        errorState.setVisibility(error ? View.VISIBLE : View.GONE);
        if (error) {
            errorText.setText(state.screen() == Screen.OFFLINE
                    ? app.lightscout.feature.stats.R.string.s03_offline
                    : app.lightscout.feature.stats.R.string.s03_error);
        }
        empty.setVisibility(state.screen() == Screen.EMPTY ? View.VISIBLE : View.GONE);
        content.setVisibility(hasStatistics ? View.VISIBLE : View.GONE);
        // The reserved height only keeps the loading / error / empty states from collapsing; the result card
        // itself hugs its content, with no empty space under the last metric.
        resultFrame.setMinimumHeight(hasStatistics ? 0 : Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 352, getResources().getDisplayMetrics())));
        if (hasStatistics) {
            renderStatistics(state.statistics());
        } else if (state.screen() == Screen.EMPTY) {
            clearStatistics();
        }
    }

    private void renderStatistics(Statistics data) {
        metrics.removeAllViews();
        distributionBar.setVisibility(View.VISIBLE);
        legend.setVisibility(View.VISIBLE);
        distributionBar.setStatistics(data);
        renderLegend(data);
        addMetric(app.lightscout.feature.stats.R.string.s03_metric_mean,
                getString(app.lightscout.feature.stats.R.string.s03_metric_mean_value,
                        roundHalfUp(data.meanMgdl())), null, null);
        addMetric(app.lightscout.feature.stats.R.string.s03_metric_cv,
                percent(roundPercent(data.cvPct())), null, null);
        addMetric(app.lightscout.feature.stats.R.string.s03_metric_gmi,
                getString(app.lightscout.feature.stats.R.string.s03_metric_gmi_value,
                        decimal1(data.gmiPct())), null, null);
    }

    private void clearStatistics() {
        distributionBar.setStatistics(null);
        distributionBar.setVisibility(View.GONE);
        legend.removeAllViews();
        legend.setVisibility(View.GONE);
        metrics.removeAllViews();
    }

    private void renderLegend(Statistics data) {
        legend.removeAllViews();
        double[] values = {data.veryHighPct(), data.highPct(), data.inRangePct(), data.lowPct(),
                data.veryLowPct()};
        int[] labels = {app.lightscout.feature.stats.R.string.s03_band_very_high,
                app.lightscout.feature.stats.R.string.s03_band_high,
                app.lightscout.feature.stats.R.string.s03_band_in_range,
                app.lightscout.feature.stats.R.string.s03_band_low,
                app.lightscout.feature.stats.R.string.s03_band_very_low};
        int[] fillColors = statisticsBandColors();
        for (int i = 0; i < values.length; i++) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setFocusable(true);
            row.setScreenReaderFocusable(true);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

            View swatch = new View(requireContext());
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(16), dp(16));
            swatchParams.setMarginEnd(dp(12));
            swatch.setLayoutParams(swatchParams);
            android.graphics.drawable.GradientDrawable swatchShape = new android.graphics.drawable.GradientDrawable();
            swatchShape.setCornerRadius(dp(3));
            swatchShape.setColor(fillColors[i]);
            swatch.setBackground(swatchShape);
            swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(swatch);

            TextView label = new TextView(requireContext());
            label.setText(labels[i]);
            setThemeTextAppearance(label, com.google.android.material.R.attr.textAppearanceBodyMedium);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(label, labelParams);

            TextView value = new TextView(requireContext());
            value.setText(percent(roundPercent(values[i])));
            setThemeTextAppearance(value, com.google.android.material.R.attr.textAppearanceBodyMedium);
            value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(value);
            row.setContentDescription(label.getText() + ", " + value.getText());
            legend.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private int[] statisticsBandColors() {
        return new int[] {
                requireContext().getColor(app.lightscout.ui.core.R.color.glass_band_very_high),
                requireContext().getColor(app.lightscout.ui.core.R.color.glass_band_high),
                requireContext().getColor(app.lightscout.ui.core.R.color.glass_band_in_range),
                requireContext().getColor(app.lightscout.ui.core.R.color.glass_band_low),
                requireContext().getColor(app.lightscout.ui.core.R.color.glass_band_very_low)
        };
    }

    private void setThemeTextAppearance(TextView view, int attribute) {
        TypedValue value = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(attribute, value, true) && value.resourceId != 0) {
            view.setTextAppearance(value.resourceId);
        }
    }

    private void addMetric(int labelId, String valueText, @Nullable String supportText,
                           @Nullable String accessibleText) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setFocusable(true);
        row.setScreenReaderFocusable(true);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout primary = new LinearLayout(requireContext());
        primary.setGravity(android.view.Gravity.CENTER_VERTICAL);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = new TextView(requireContext());
        label.setText(labelId);
        setThemeTextAppearance(label, com.google.android.material.R.attr.textAppearanceBodyLarge);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        primary.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(requireContext());
        value.setText(valueText);
        setThemeTextAppearance(value, com.google.android.material.R.attr.textAppearanceTitleMedium);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        primary.addView(value);
        row.addView(primary);

        if (supportText != null) {
            TextView support = new TextView(requireContext());
            support.setText(supportText);
            setThemeTextAppearance(support, com.google.android.material.R.attr.textAppearanceBodySmall);
            support.setTextColor(MaterialColors.getColor(support, com.google.android.material.R.attr.colorOnSurfaceVariant));
            support.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(support);
            if (accessibleText == null) {
                accessibleText = label.getText() + ", " + value.getText() + ", " + support.getText();
            }
        } else if (accessibleText == null) {
            accessibleText = label.getText() + ", " + value.getText();
        }
        row.setContentDescription(accessibleText);
        if (metrics.getChildCount() > 0) {
            // Dividers sit between metrics only, never under the last one.
            View separator = new View(requireContext());
            separator.setBackgroundColor(MaterialColors.getColor(row,
                    com.google.android.material.R.attr.colorOutlineVariant));
            metrics.addView(separator, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        metrics.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String percent(int value) {
        return getString(app.lightscout.feature.stats.R.string.s03_percent, value);
    }

    private static int roundPercent(double value) {
        return roundHalfUp(value);
    }

    private static int roundHalfUp(double value) {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static String decimal1(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int buttonFor(PeriodKind kind) {
        return switch (kind) {
            case YESTERDAY -> app.lightscout.feature.stats.R.id.s03_period_yesterday;
            case LAST_7_DAYS -> app.lightscout.feature.stats.R.id.s03_period_7_days;
            case LAST_14_DAYS -> app.lightscout.feature.stats.R.id.s03_period_14_days;
            case LAST_30_DAYS -> app.lightscout.feature.stats.R.id.s03_period_30_days;
            case LAST_24_HOURS -> throw new IllegalArgumentException("S-03 does not offer Last 24 hours");
        };
    }

    private static PeriodKind periodFor(int buttonId) {
        if (buttonId == app.lightscout.feature.stats.R.id.s03_period_yesterday) {
            return PeriodKind.YESTERDAY;
        }
        if (buttonId == app.lightscout.feature.stats.R.id.s03_period_7_days) {
            return PeriodKind.LAST_7_DAYS;
        }
        if (buttonId == app.lightscout.feature.stats.R.id.s03_period_14_days) {
            return PeriodKind.LAST_14_DAYS;
        }
        if (buttonId == app.lightscout.feature.stats.R.id.s03_period_30_days) {
            return PeriodKind.LAST_30_DAYS;
        }
        throw new IllegalArgumentException("Unknown statistics period button " + buttonId);
    }
}
