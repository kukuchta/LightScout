package app.lightscout.feature.home;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.res.Configuration;
import android.net.Uri;
import android.view.accessibility.AccessibilityEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.viewmodel.CreationExtras;

import java.util.List;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.statistics.Statistics;
import app.lightscout.feature.home.HomeUiState.BannerUi;
import app.lightscout.feature.home.HomeUiState.RangeLabel;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.EdgeToEdgeInsets;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.NavResults;
import app.lightscout.ui.core.TrendIcons;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;

/**
 * S-01 Home (CMP-70; CP-12): renders the status, chart, counters and doughnut; data rules live in
 * {@link HomeModel} / {@link HomeViewModel}. It requests no permission and never redirects on connection errors.
 */
public final class HomeFragment extends Fragment {

    /** Time an announcement needs before the live region may be switched off for a quiet update (IR-103). */
    private static final long ANNOUNCE_SETTLE_MS = 1_500L;

    private static final java.time.format.DateTimeFormatter SNOOZE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = this::onMinuteTick;
    private HomeViewModel viewModel;
    private Clock clock;
    private app.lightscout.domain.port.SupportPromptStore supportStore;
    private java.util.concurrent.Executor supportIo;
    private final app.lightscout.domain.support.SupportPromptPolicy supportPolicy =
            new app.lightscout.domain.support.SupportPromptPolicy();
    private boolean supportPromptChecked;
    /** Set on the main thread when the one-time prompt appears: closes the window before the flag is stored. */
    private static volatile boolean supportPromptShownInProcess;

    private final MaterialButton[] bannerSlots = new MaterialButton[4];
    private final boolean[] bannerRendered = new boolean[4];
    private final BannerUi[] renderedBanners = new BannerUi[4];
    private AlertDialog snoozeDialog;
    private AlertDialog supportDialog;
    private static final float SUPPORT_DIM_AMOUNT = 0.6f;
    private static final int SUPPORT_BLUR_RADIUS_PX = 24;
    private AlertDialog details;
    private HomeUiState.BannerKind detailsKind;
    private MaterialCardView hero;
    private TextView staleChip;
    private View readingGroup;
    private TextView reading;
    private TextView delta;
    private ImageView trend;
    private TextView rangeLabel;
    private TextView age;
    private TextView iob;
    private FreshnessTrackView nextReading;
    private GlucoseChartView chart;
    private View nowChip;
    private LinearLayout counters;
    private MaterialCardView siteCard;
    private MaterialCardView sensorCard;
    private TextView siteValue;
    private TextView sensorValue;
    private View siteStatus;
    private View sensorStatus;
    private ImageView siteStatusIcon;
    private ImageView sensorStatusIcon;
    private TextView siteStatusText;
    private TextView sensorStatusText;
    private MaterialCardView doughnutCard;
    private HomeDistributionView distribution;
    private TextView doughnutLegend;
    private TextView doughnutSummary;
    private TextView doughnutEmpty;
    private View dock;
    private long lastAnnounceUptimeMs;
    private MaterialButton snoozeChip;

    /** Creates the fragment (no-argument constructor required by the fragment framework). */
    public HomeFragment() {
        super(R.layout.fragment_home);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        View root = getView();
        if (root != null) {
            configureHomeLayout(root, root.findViewById(R.id.s01_scroll));
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Dependencies deps = HomeHost.dependencies(this);
        final StatusRepository status = deps.get(StatusRepository.class);
        final AlarmActions alarms = deps.get(AlarmActions.class);
        clock = deps.get(Clock.class);
        HistoryLoader history = deps.get(HistoryLoader.class);
        SettingsService settings = deps.get(SettingsService.class);
        java.util.concurrent.Executor ioExecutor = deps.ioExecutor();
        supportStore = deps.get(app.lightscout.domain.port.SupportPromptStore.class);
        supportIo = ioExecutor;
        viewModel = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass, @NonNull CreationExtras extras) {
                if (!modelClass.isAssignableFrom(HomeViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel " + modelClass.getName());
                }
                return modelClass.cast(new HomeViewModel(status, alarms, clock, new MainThreadTimer(),
                        history, settings, ioExecutor));
            }
        }).get(HomeViewModel.class);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        MaterialToolbar toolbar = view.findViewById(R.id.s01_toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.s01_action_support) {
                showSupportDialog();
                return true;
            }
            if (item.getItemId() == R.id.s01_action_settings) {
                viewModel.model().onSettings();
                return true;
            }
            if (item.getItemId() == R.id.s01_action_statistics) {
                viewModel.model().onStatistics();
                return true;
            }
            return false;
        });
        EdgeToEdgeInsets.applyStatusBarInset(view.findViewById(R.id.s01_app_bar));
        HomeContentScrollView scroll = view.findViewById(R.id.s01_scroll);
        dock = view.findViewById(R.id.s01_dock);
        EdgeToEdgeInsets.applyImeInset(dock);
        view.findViewById(R.id.s01_carbs).setOnClickListener(v -> viewModel.model().onNewEntry(EntryType.CARBS));
        view.findViewById(R.id.s01_bolus).setOnClickListener(v -> viewModel.model().onNewEntry(EntryType.BOLUS));
        view.findViewById(R.id.s01_bg).setOnClickListener(v -> viewModel.model().onNewEntry(EntryType.BG_CHECK));
        styleDockButtons(view);
        scroll.setOnScrollChangeListener((NestedScrollView v, int scrollX, int scrollY, int oldScrollX,
                                         int oldScrollY) -> {
            if (nextReading != null) {
                nextReading.updateAnimation();
            }
        });

        bannerSlots[0] = view.findViewById(R.id.s01_banner);
        bannerSlots[1] = view.findViewById(R.id.s01_banner_readiness);
        bannerSlots[2] = view.findViewById(R.id.s01_banner_connection);
        bannerSlots[3] = view.findViewById(R.id.s01_banner_offline);
        snoozeChip = view.findViewById(R.id.s01_snooze_chip);
        snoozeChip.setOnClickListener(v -> showSnoozeWindow());
        hero = view.findViewById(R.id.s01_hero);
        staleChip = view.findViewById(R.id.s01_stale);
        readingGroup = view.findViewById(R.id.s01_reading_group);
        reading = view.findViewById(R.id.s01_reading);
        trend = view.findViewById(R.id.s01_trend);
        rangeLabel = view.findViewById(R.id.s01_range_label);
        age = view.findViewById(R.id.s01_age);
        iob = view.findViewById(R.id.s01_iob);
        delta = view.findViewById(R.id.s01_delta);
        nextReading = view.findViewById(R.id.s01_next_reading);
        chart = view.findViewById(R.id.s01_chart);
        nowChip = view.findViewById(R.id.s01_now_chip);
        nowChip.setOnClickListener(v -> chart.resetToNow());
        counters = view.findViewById(R.id.s01_counters);
        siteCard = view.findViewById(R.id.s01_site_card);
        sensorCard = view.findViewById(R.id.s01_sensor_card);
        siteValue = view.findViewById(R.id.s01_site_value);
        sensorValue = view.findViewById(R.id.s01_sensor_value);
        siteStatus = view.findViewById(R.id.s01_site_status);
        sensorStatus = view.findViewById(R.id.s01_sensor_status);
        siteStatusIcon = view.findViewById(R.id.s01_site_status_icon);
        sensorStatusIcon = view.findViewById(R.id.s01_sensor_status_icon);
        siteStatusText = view.findViewById(R.id.s01_site_status_text);
        sensorStatusText = view.findViewById(R.id.s01_sensor_status_text);
        doughnutCard = view.findViewById(R.id.s01_doughnut_card);
        distribution = view.findViewById(R.id.s01_distribution);
        doughnutLegend = view.findViewById(R.id.s01_doughnut_legend);
        doughnutSummary = view.findViewById(R.id.s01_doughnut_summary);
        doughnutEmpty = view.findViewById(R.id.s01_doughnut_empty);
        configureHomeLayout(view, scroll);
        for (int i = 0; i < bannerSlots.length; i++) {
            int slot = i;
            bannerSlots[i].setOnClickListener(v -> showDetails(slot));
        }
        view.findViewById(R.id.s01_site_entry).setOnClickListener(v -> viewModel.model().onNewEntry(EntryType.SITE_CHANGE));
        view.findViewById(R.id.s01_sensor_entry).setOnClickListener(v -> viewModel.model().onNewEntry(EntryType.SENSOR_CHANGE));
        doughnutCard.setOnClickListener(v -> viewModel.model().onStatistics());
        configureChart();

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.commands().observe(getViewLifecycleOwner(), event -> {
            HomeCommand c = event == null ? null : event.take();
            if (c != null) {
                HomeNavigation.execute(c, HomeHost.navigator(this), viewModel.model().takePendingEntryIdentifier());
                // IR-101: if S-01 is still resumed on the next frame, no transition started — release the guard.
                view.postOnAnimation(() -> {
                    if (isResumed()) {
                        viewModel.model().onNavigationIdle();
                    }
                });
            }
        });
        viewModel.chartResults().observe(getViewLifecycleOwner(), event -> {
            Long signal = event == null ? null : event.take();
            if (signal == null || chart == null) {
                return;
            }
            for (HomeViewModel.ChartResult result : viewModel.takeChartResults()) {
                applyChartResult(result);
            }
        });
        NavResults.observe(this, R.id.homeFragment, this::onResult);
    }

    @Override
    public void onStart() {
        super.onStart();
        nextReading.setStarted(true);
        onMinuteTick();
    }

    @Override
    public void onResume() {
        super.onResume();
        supportPromptChecked = false;
        if (viewModel.onResume() && chart != null) {
            chart.clearHistoryData();
            viewModel.prepareChartCache();
        }
    }

    @Override
    public void onPause() {
        viewModel.model().onPause();
        super.onPause();
    }

    @Override
    public void onStop() {
        mainHandler.removeCallbacks(minuteTick);
        nextReading.setStarted(false);
        closeDetails();
        closeSnoozeWindow();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        closeDetails();
        closeSnoozeWindow();
        closeSupportDialog();
        java.util.Arrays.fill(bannerSlots, null);
        snoozeChip = null;
        hero = null;
        staleChip = null;
        readingGroup = null;
        reading = null;
        trend = null;
        rangeLabel = null;
        age = null;
        iob = null;
        nextReading = null;
        if (chart != null) {
            chart.setListener(null);
        }
        chart = null;
        nowChip = null;
        counters = null;
        siteCard = null;
        sensorCard = null;
        siteValue = null;
        sensorValue = null;
        siteStatus = null;
        sensorStatus = null;
        siteStatusIcon = null;
        sensorStatusIcon = null;
        siteStatusText = null;
        sensorStatusText = null;
        doughnutCard = null;
        distribution = null;
        doughnutLegend = null;
        doughnutSummary = null;
        doughnutEmpty = null;
        dock = null;
        java.util.Arrays.fill(bannerRendered, false);
        java.util.Arrays.fill(renderedBanners, null);
        super.onDestroyView();
    }

    /** Minute refresh while started (ADR-049): re-maps the age and re-arms at the next age-minute boundary. */
    private void onMinuteTick() {
        mainHandler.removeCallbacks(minuteTick);
        viewModel.model().onMinute();
        mainHandler.postDelayed(minuteTick, viewModel.model().nextRefreshDelayMs());
    }

    private void onResult(UiMessage message) {
        if (getView() == null) {
            return;
        }
        int text;
        if (message == UiMessage.CONNECTION_SAVED) {
            text = app.lightscout.ui.core.R.string.s06_snackbar_saved;
        } else if (message == UiMessage.ENTRY_SAVED) {
            text = R.string.s01_snackbar_entry_saved;
        } else if (message == UiMessage.ENTRY_UPDATED) {
            text = R.string.s01_snackbar_entry_updated;
        } else if (message == UiMessage.ENTRY_DELETED) {
            text = R.string.s01_snackbar_entry_deleted;
        } else {
            return;
        }
        if (message == UiMessage.ENTRY_SAVED || message == UiMessage.ENTRY_UPDATED
                || message == UiMessage.ENTRY_DELETED) {
            viewModel.onChartDataChanged();
        }
        Snackbar.make(getView(), text, Snackbar.LENGTH_SHORT).setAnchorView(dock).show();
    }

    private void render(HomeUiState s) {
        if (reading == null || s == null) {
            return;
        }
        renderBanners(s.banners());
        renderSnooze(s);
        maybeShowSupportPrompt(s);
        renderReading(s);
        renderProgress(s);
        renderChart(s);
        renderCounters(s);
        renderDoughnut(s);
    }

    private void configureChart() {
        chart.setNow(clock.now(), clock.zone());
        chart.setViewport(viewModel.chartViewport(clock.now()));
        HomeViewModel.ChartResult prepared = viewModel.preparedChartCache();
        if (prepared != null) {
            applyChartResult(prepared);
        }
        viewModel.prepareChartCache();
        chart.setListener(new GlucoseChartView.Listener() {
            @Override
            public void onViewportChanged(ChartViewport viewport, boolean atNow) {
                viewModel.onViewportChanged(viewport);
                nowChip.setVisibility(atNow ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onWindowNeeded(java.time.Instant from, java.time.Instant to) {
                chart.setSegmentState(from, to, SegmentState.LOADING);
                viewModel.loadSegment(from, to);
            }

            @Override
            public void onEditRequested(String entryId) {
                viewModel.model().onEditEntry(entryId);
            }
        });
    }

    private void applyChartResult(HomeViewModel.ChartResult result) {
        if (chart == null) {
            return;
        }
        if (result.cacheOnly()) {
            if (result.segment() != null) {
                chart.setCachedSegment(result.segment());
            }
            if (!result.success()) {
                chart.setCachePreparationError(result.from(), result.to());
            }
            return;
        }
        if (result.success()) {
            chart.setSegment(result.segment());
        } else {
            SegmentState state = result.outcome() == app.lightscout.domain.port.FetchOutcome.NO_INTERNET
                    ? SegmentState.OFFLINE : SegmentState.ERROR;
            chart.setSegmentState(result.from(), result.to(), state);
        }
    }

    private void renderChart(HomeUiState s) {
        chart.setNow(s.now(), s.zone());
        chart.setViewport(chart.getViewport());
        chart.setThresholds(s.snapshot().urgentLowMgdl(), s.snapshot().lowMgdl(), s.snapshot().highMgdl());
        int empty = HomeCopy.emptyMessage(s.emptyMessage());
        chart.setEmptyMessage(empty == 0 ? null : getText(empty));
        String value = s.sgv() == null ? getString(app.lightscout.ui.core.R.string.glass_value_empty)
                : Integer.toString(s.sgv());
        chart.setSummary(getString(R.string.s01_chart_a11y, value,
                Formatters.trendWords(requireContext(), s.trend())));
    }

    private void renderCounters(HomeUiState s) {
        renderCounter(siteValue, siteStatus, siteStatusIcon, siteStatusText, s.siteCounter(), true);
        renderCounter(sensorValue, sensorStatus, sensorStatusIcon, sensorStatusText, s.sensorCounter(), false);
        boolean stack = getResources().getConfiguration().fontScale >= 1.5f;
        counters.setOrientation(stack ? android.widget.LinearLayout.VERTICAL : android.widget.LinearLayout.HORIZONTAL);
        setCounterWidth(siteCard, stack, true);
        setCounterWidth(sensorCard, stack, false);
    }

    private void renderCounter(TextView value, View status, ImageView icon, TextView statusText,
                               CounterPresentation counter, boolean site) {
        value.setText(counter.age() == null ? getString(app.lightscout.ui.core.R.string.glass_value_empty)
                : Formatters.consumableAge(requireContext(), counter.age()));
        int label = R.string.s01_counter_ok;
        int color = MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnSurface);
        if (counter.state() == CounterPresentation.State.CHANGE_SOON) {
            label = R.string.s01_counter_replace_soon;
            color = statusColor(false);
        } else if (counter.state() == CounterPresentation.State.EXPIRED) {
            label = R.string.s01_counter_expired;
            color = statusColor(true);
        } else if (counter.state() == CounterPresentation.State.LOADING) {
            label = R.string.s01_counter_loading;
            color = MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnSurfaceVariant);
        } else if (counter.state() == CounterPresentation.State.NONE_FOUND) {
            label = site ? R.string.s01_counter_no_site_change : R.string.s01_counter_no_sensor_change;
            color = MaterialColors.getColor(statusText, com.google.android.material.R.attr.colorOnSurfaceVariant);
        }
        statusText.setText(label);
        statusText.setTextColor(color);
        // DCR-47: like the hero reading, the counter value takes the warning / expired colour (card stays neutral)
        boolean warn = counter.state() == CounterPresentation.State.CHANGE_SOON
                || counter.state() == CounterPresentation.State.EXPIRED;
        value.setTextColor(warn ? color : MaterialColors.getColor(value,
                com.google.android.material.R.attr.colorOnSurface));
        if (counter.state() == CounterPresentation.State.CHANGE_SOON
                || counter.state() == CounterPresentation.State.EXPIRED) {
            icon.setImageResource(R.drawable.ic_s01_counter_warning);
            icon.setImageTintList(ColorStateList.valueOf(color));
            icon.setVisibility(View.VISIBLE);
        } else {
            icon.setVisibility(View.GONE);
        }
        // Without the icon the label is wider, but the card keeps the height it needs with it (stable card bounds).
        ((StateStableTextView) statusText).setReservedInset(
                icon.getVisibility() == View.VISIBLE ? 0 : Math.round(24 * getResources().getDisplayMetrics().density));
        status.setVisibility(View.VISIBLE);
    }

    private void setCounterWidth(MaterialCardView card, boolean stack, boolean first) {
        android.widget.LinearLayout.LayoutParams params =
                (android.widget.LinearLayout.LayoutParams) card.getLayoutParams();
        params.width = stack ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        params.weight = stack ? 0 : 1;
        params.setMarginStart(stack ? 0 : (first ? 0 : dp(4)));
        params.setMarginEnd(stack ? 0 : (first ? dp(4) : 0));
        if (stack) {
            params.bottomMargin = dp(first ? 12 : 0);
        } else {
            params.bottomMargin = 0;
        }
        card.setLayoutParams(params);
    }

    private void renderDoughnut(HomeUiState s) {
        // Setting "Statistics card on home screen": when off the card is hidden and a toolbar icon opens statistics.
        boolean cardShown = s.snapshot() == null || s.snapshot().statisticsCardOnHome();
        doughnutCard.setVisibility(cardShown ? View.VISIBLE : View.GONE);
        MaterialToolbar bar = requireView().findViewById(R.id.s01_toolbar);
        android.view.MenuItem statisticsItem = bar.getMenu().findItem(R.id.s01_action_statistics);
        if (statisticsItem != null) {
            statisticsItem.setVisible(!cardShown);
        }
        if (!cardShown) {
            return;
        }
        Statistics stats = s.statistics();
        distribution.setStatistics(stats);
        HomeDistributionView.Values values = stats == null ? null : HomeDistributionView.values(stats);
        boolean enough = s.statisticsLoaded() && values != null && values.enoughData();
        doughnutCard.setContentDescription(enough
                ? getString(R.string.s01_doughnut_a11y, values.veryLow(), values.low(), values.inRange(),
                        values.high(), values.veryHigh())
                : getString(R.string.s01_doughnut_empty_a11y));
        doughnutEmpty.setVisibility(s.statisticsLoaded() && !enough ? View.VISIBLE : View.INVISIBLE);
        doughnutLegend.setVisibility(enough ? View.VISIBLE : View.INVISIBLE);
        doughnutSummary.setVisibility(enough ? View.VISIBLE : View.INVISIBLE);
        if (enough) {
            doughnutSummary.setText(getString(R.string.s01_range_in_range) + " "
                    + getString(R.string.s01_doughnut_centre, values.inRange()));
            String plain = getString(R.string.s01_doughnut_legend, values.veryLow(), values.low(),
                    values.inRange(), values.high(), values.veryHigh());
            doughnutLegend.setText(coloredLegend(plain));
        }
    }

    /** Glass legend: each percentage gets a square in its band colour; the text itself is unchanged. */
    private CharSequence coloredLegend(String plain) {
        int[] bandColors = {
                color(app.lightscout.ui.core.R.color.glass_band_very_low),
                color(app.lightscout.ui.core.R.color.glass_band_low),
                color(app.lightscout.ui.core.R.color.glass_band_in_range),
                color(app.lightscout.ui.core.R.color.glass_band_high),
                color(app.lightscout.ui.core.R.color.glass_band_very_high)};
        String[] parts = plain.split(" \u00B7 ");
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append("  ·  ");
            }
            int start = out.length();
            out.append("\u25A0");
            out.setSpan(new android.text.style.ForegroundColorSpan(bandColors[Math.min(i, bandColors.length - 1)]),
                    start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append(' ').append(parts[i]);
        }
        return out;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void renderBanners(List<BannerUi> banners) {
        if (details != null && banners.stream().noneMatch(b -> b.kind() == detailsKind)) {
            closeDetails();
        }
        for (int i = 0; i < bannerSlots.length; i++) {
            BannerUi shown = null;
            for (BannerUi b : banners) {
                if (slotOf(b.kind()) == i) {
                    shown = b;
                }
            }
            renderBanner(i, shown);
        }
    }

    /** DCR-21 chip slot of a banner kind: alarms off, readiness, connection / data loss, offline. */
    static int slotOf(HomeUiState.BannerKind kind) {
        switch (kind) {
            case ALARMS_OFF:
            case LOW_ALARM_OFF:
            case HIGH_ALARM_OFF:
            case LOW_HIGH_ALARMS_OFF:
                return 0;
            case PERMISSION_MISSING:
                return 1;
            case OFFLINE:
                return 3;
            default:
                return 2;
        }
    }

    private void renderBanner(int slot, BannerUi b) {
        MaterialButton banner = bannerSlots[slot];
        BannerChange change = BannerChange.of(!bannerRendered[slot], renderedBanners[slot], b);
        bannerRendered[slot] = true;
        renderedBanners[slot] = b;
        if (b == null) {
            banner.setVisibility(View.GONE);
            return;
        }
        if (change == BannerChange.ANNOUNCE) {
            banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            lastAnnounceUptimeMs = SystemClock.uptimeMillis();
        } else if (SystemClock.uptimeMillis() - lastAnnounceUptimeMs > ANNOUNCE_SETTLE_MS) {
            banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        }
        if (change == BannerChange.INITIAL || change == BannerChange.ANNOUNCE) {
            banner.setText(HomeCopy.chipText(b.kind()));
        }
        styleChip(banner, tierOf(b.kind()));
        banner.setVisibility(View.VISIBLE);
        if (details != null && detailsKind == b.kind()) {
            details.setMessage(bannerDetails(b));
            if (details.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                details.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(b.actionEnabled());
            }
        }
    }

    /** Chip tiers: red = alarms or data not working, yellow = degraded, blue = information. */
    enum ChipTier { WARNING, CAUTION, INFO }

    static ChipTier tierOf(HomeUiState.BannerKind kind) {
        switch (kind) {
            case LOW_ALARM_OFF:
            case HIGH_ALARM_OFF:
            case TOKEN_PERMISSION_WRITE:
                return ChipTier.CAUTION;
            case OFFLINE:
                return ChipTier.INFO;
            default:
                return ChipTier.WARNING;
        }
    }

    /** Glass dock: white labels and icons in the colours of the matching chart markers (BG red). */
    private void styleDockButtons(View view) {
        int[] ids = {R.id.s01_carbs, R.id.s01_bolus, R.id.s01_bg};
        int[] tints = {app.lightscout.ui.core.R.color.glass_marker_carbs,
                app.lightscout.ui.core.R.color.glass_marker_bolus, app.lightscout.ui.core.R.color.glass_marker_bg};
        for (int i = 0; i < ids.length; i++) {
            MaterialButton button = view.findViewById(ids[i]);
            button.setTextColor(color(app.lightscout.ui.core.R.color.glass_text));
            button.setIconTint(ColorStateList.valueOf(color(tints[i])));
        }
    }

    private void styleChip(MaterialButton chip, ChipTier tier) {
        int background;
        int text;
        int border;
        switch (tier) {
            case INFO:
                background = app.lightscout.ui.core.R.color.glass_chip_info_background;
                text = app.lightscout.ui.core.R.color.glass_chip_info_text;
                border = app.lightscout.ui.core.R.color.glass_chip_info_border;
                break;
            case CAUTION:
                background = app.lightscout.ui.core.R.color.glass_chip_caution_background;
                text = app.lightscout.ui.core.R.color.glass_chip_caution_text;
                border = app.lightscout.ui.core.R.color.glass_chip_caution_border;
                break;
            default:
                background = app.lightscout.ui.core.R.color.glass_chip_warning_background;
                text = app.lightscout.ui.core.R.color.glass_chip_warning_text;
                border = app.lightscout.ui.core.R.color.glass_chip_warning_border;
                break;
        }
        chip.setBackgroundTintList(ColorStateList.valueOf(color(background)));
        chip.setTextColor(color(text));
        chip.setStrokeColor(ColorStateList.valueOf(color(border)));
        chip.setStrokeWidth(dp(1));
    }

    private String bannerDetails(BannerUi b) {
        return HomeCopy.bannerTakesMinutes(b.kind()) ? getString(HomeCopy.bannerText(b.kind()), b.minutes())
                : getString(HomeCopy.bannerText(b.kind()));
    }

    @android.annotation.SuppressLint("AccessibilityFocus") // R-93 requires returning focus to the invoking chip.
    private void showDetails(int slot) {
        BannerUi b = renderedBanners[slot];
        if (b == null || details != null) {
            return;
        }
        MaterialButton invoker = bannerSlots[slot];
        detailsKind = b.kind();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(HomeCopy.chipText(b.kind())).setMessage(bannerDetails(b));
        int action = HomeCopy.bannerAction(b.action());
        builder.setPositiveButton(action != 0 ? action : android.R.string.ok, null);
        details = builder.create();
        details.setOnDismissListener(dialog -> {
            details = null;
            detailsKind = null;
            if (bannerSlots[slot] != null && invoker.getVisibility() == View.VISIBLE) {
                invoker.requestFocus();
                invoker.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                invoker.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            }
        });
        details.show();
        details.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(action == 0 || b.actionEnabled());
        if (action != 0) {
            details.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                viewModel.model().onBannerAction(b.kind());
                closeDetails();
            });
        }
    }

    private void closeDetails() {
        if (details != null) {
            details.dismiss();
        }
    }

    private List<SnoozeRows> snoozeRows(HomeUiState s) {
        return SnoozeRows.of(s.snapshot().snoozes(), s.now(), s.zone());
    }

    private void renderSnooze(HomeUiState s) {
        List<SnoozeRows> rows = snoozeRows(s);
        styleChip(snoozeChip, ChipTier.INFO);
        snoozeChip.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (snoozeDialog == null) {
            return;
        }
        if (rows.isEmpty()) {
            closeSnoozeWindow();
        } else {
            snoozeDialog.setMessage(snoozeText(rows));
        }
    }

    private String snoozeText(List<SnoozeRows> rows) {
        StringBuilder out = new StringBuilder();
        for (SnoozeRows r : rows) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(getString(R.string.s01_snooze_row, getString(HomeCopy.snoozeType(r.type())),
                    r.endsAt().format(SNOOZE_TIME), r.minutesLeft()));
        }
        return out.toString();
    }

    @android.annotation.SuppressLint("AccessibilityFocus") // R-93 requires returning focus to the invoking chip.
    private void showSnoozeWindow() {
        List<SnoozeRows> rows = snoozeRows(viewModel.model().state());
        if (rows.isEmpty() || snoozeDialog != null) {
            return;
        }
        snoozeDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.s01_snooze_title).setMessage(snoozeText(rows))
                .setPositiveButton(android.R.string.ok, null).create();
        snoozeDialog.setOnDismissListener(dialog -> {
            snoozeDialog = null;
            if (snoozeChip != null && snoozeChip.getVisibility() == View.VISIBLE) {
                snoozeChip.requestFocus();
                snoozeChip.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                snoozeChip.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            }
        });
        snoozeDialog.show();
    }

    /**
     * DCR-46 (D-166): the one-time support prompt. Checked once per resume, only in a calm moment of S-01
     * ({@link SupportPromptGate}); the schedule (7 days of use, 09:00–20:00 local) and the once-only flag are
     * decided off the main thread. The flag is set when the dialog appears, whatever the user does with it.
     */
    private void maybeShowSupportPrompt(HomeUiState s) {
        if (supportPromptChecked || supportPromptShownInProcess || supportStore == null || !isResumed()
                || supportDialog != null
                || snoozeDialog != null || !SupportPromptGate.isCalm(s)) {
            return;
        }
        supportPromptChecked = true;
        final java.time.Instant now = clock.now();
        final java.time.ZoneId zone = s.zone();
        supportIo.execute(() -> {
            boolean show;
            try {
                show = supportPolicy.shouldShow(now, zone, supportStore.firstUse(now), supportStore.shown(), true);
            } catch (RuntimeException e) {
                show = false;   // a storage failure never blocks Home; asked again on the next resume
            }
            final boolean fire = show;
            android.app.Activity activity = getActivity();
            if (!fire || activity == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                HomeUiState latest = viewModel.state().getValue();
                if (!isResumed() || getView() == null || supportDialog != null || snoozeDialog != null
                        || supportPromptShownInProcess || latest == null || !SupportPromptGate.isCalm(latest)) {
                    supportPromptChecked = false;   // not shown: ask again on the next render
                    return;
                }
                supportPromptShownInProcess = true;
                showSupportDialog();
                supportIo.execute(() -> {
                    try {
                        supportStore.markShown();
                    } catch (RuntimeException e) {
                        // asked again next time; harmless
                    }
                });
            });
        });
    }

    /** DCR-43 / DCR-46: the support dialog; the toolbar heart opens it at any time, the one-time prompt once. */
    private void showSupportDialog() {
        if (supportDialog != null) {
            return;
        }
        supportDialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_LightScout_SupportDialog)
                .setCustomTitle(getLayoutInflater().inflate(R.layout.s01_support_title, null))
                .setMessage(R.string.s01_support_message)
                .setPositiveButton(R.string.s01_support_open, (dialog, which) -> {
                    markSupportPromptShown();   // DCR-46: who donates is not asked again
                    openSupportLink();
                })
                .setNegativeButton(R.string.s01_support_dismiss, null).create();
        supportDialog.setOnDismissListener(dialog -> supportDialog = null);
        dimBehind(supportDialog);
        supportDialog.show();
    }

    /** DCR-46: a stronger dim behind the support dialog, plus a blur where the system supports it (API 31+). */
    private static void dimBehind(AlertDialog dialog) {
        android.view.Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(SUPPORT_DIM_AMOUNT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            android.view.WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.setBlurBehindRadius(SUPPORT_BLUR_RADIUS_PX);
            window.setAttributes(attributes);
        }
    }

    private void markSupportPromptShown() {
        if (supportStore == null || supportIo == null) {
            return;
        }
        supportIo.execute(() -> {
            try {
                supportStore.markShown();
            } catch (RuntimeException e) {
                // asked again next time; harmless
            }
        });
    }

    private void openSupportLink() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.s01_support_url)))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            View root = getView();
            if (root != null) {
                Snackbar.make(root, R.string.s01_support_open_failed, Snackbar.LENGTH_LONG).setAnchorView(dock).show();
            }
        }
    }

    private void closeSupportDialog() {
        if (supportDialog != null) {
            supportDialog.dismiss();
        }
    }

    private void closeSnoozeWindow() {
        if (snoozeDialog != null) {
            snoozeDialog.dismiss();
        }
    }

    private int color(int id) {
        return requireContext().getColor(id);
    }

    private int statusColor(boolean late) {
        return color(late ? app.lightscout.ui.core.R.color.glass_reading_low
                : app.lightscout.ui.core.R.color.glass_reading_high);
    }

    private void renderReading(HomeUiState s) {
        int onSurface = MaterialColors.getColor(reading, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = MaterialColors.getColor(reading,
                com.google.android.material.R.attr.colorOnSurfaceVariant);

        reading.setText(s.sgv() == null ? getString(app.lightscout.ui.core.R.string.glass_value_empty)
                : Integer.toString(s.sgv()));
        if (s.deltaMgdl() == null) {
            delta.setText(getString(app.lightscout.ui.core.R.string.glass_value_empty) + " " + getString(R.string.s01_unit_mgdl));
        } else {
            delta.setText(GlucoseDelta.signedText(s.deltaMgdl()) + " " + getString(R.string.s01_unit_mgdl));
        }
        // §7.5 error extras: reading greyed and age in error role when ≥ data-loss threshold.
        int readingColor = onSurface;
        readingColor = color(switch (s.rangeLabel()) {
            case NONE -> app.lightscout.ui.core.R.color.glass_reading_normal;
            case HIGH -> app.lightscout.ui.core.R.color.glass_reading_high;
            case LOW -> app.lightscout.ui.core.R.color.glass_reading_low;
            case URGENT_LOW -> app.lightscout.ui.core.R.color.glass_reading_urgent_low;
        });
        // The tint lies over the card (white in light, translucent white in dark), it does not replace it.
        int cardColor = color(app.lightscout.ui.core.R.color.glass_card);
        hero.setCardBackgroundColor(switch (s.rangeLabel()) {
            case NONE -> cardColor;
            case HIGH -> androidx.core.graphics.ColorUtils.compositeColors(
                    color(app.lightscout.ui.core.R.color.glass_hero_tint_high), cardColor);
            case LOW -> androidx.core.graphics.ColorUtils.compositeColors(
                    color(app.lightscout.ui.core.R.color.glass_hero_tint_low), cardColor);
            case URGENT_LOW -> androidx.core.graphics.ColorUtils.compositeColors(
                    color(app.lightscout.ui.core.R.color.glass_hero_tint_urgent_low), cardColor);
        });
        hero.setStrokeColor(color(switch (s.rangeLabel()) {
            case NONE -> app.lightscout.ui.core.R.color.glass_hero_border;
            case HIGH -> app.lightscout.ui.core.R.color.glass_hero_border_high;
            case LOW -> app.lightscout.ui.core.R.color.glass_hero_border_low;
            case URGENT_LOW -> app.lightscout.ui.core.R.color.glass_hero_border_urgent_low;
        }));
    
        reading.setTextColor(s.stale() ? onSurfaceVariant : readingColor);
        trend.setImageTintList(ColorStateList.valueOf(s.stale() ? onSurfaceVariant : readingColor));
        staleChip.setVisibility(s.stale() ? View.VISIBLE : View.GONE);

        int arrow = s.trend() == null ? 0 : TrendIcons.drawableFor(s.trend());
        if (arrow == 0) {
            trend.setImageDrawable(null);
            trend.setContentDescription(null);
            trend.setVisibility(View.INVISIBLE);
        } else {
            trend.setImageResource(arrow);
            trend.setContentDescription(Formatters.trendWords(requireContext(), s.trend()));
            trend.setVisibility(View.VISIBLE);
        }

        renderRangeLabel(s.rangeLabel(), s.sgv() != null);

        age.setText(s.age() == null ? getString(HomeCopy.emptyMessage(s.emptyMessage()))
                : Formatters.readingAge(requireContext(), s.age()));
        FreshnessPresentation freshness = FreshnessPresentation.from(s);
        // D-151: the age is amber only while the track is orange (a missed check), red when there is no data.
        age.setTextColor(s.stale() ? statusColor(true)
                : freshness.phase() == FreshnessPresentation.Phase.MISSED
                ? statusColor(false) : onSurfaceVariant);

        if (s.iob() == null) {
            iob.setVisibility(View.GONE);
        } else {
            iob.setText(getString(R.string.s01_iob, s.iob()));
            iob.setVisibility(View.VISIBLE);
        }

        readingGroup.setContentDescription(readingGroupDescription(s));
    }

    private void renderRangeLabel(RangeLabel label, boolean hasReading) {
        int text = HomeCopy.rangeLabel(label);
        if (!hasReading) {
            rangeLabel.setVisibility(View.INVISIBLE);
            return;
        }
        rangeLabel.setText(text);
        switch (label) {
            case URGENT_LOW:
                rangeLabel.setBackgroundResource(app.lightscout.ui.core.R.drawable.glass_urgent_label);
                rangeLabel.setTextColor(color(app.lightscout.ui.core.R.color.glass_urgent_label_text));
                break;
            case LOW:
                glassPill(app.lightscout.ui.core.R.color.glass_range_pill_low_bg,
                        app.lightscout.ui.core.R.color.glass_range_pill_low_text,
                        app.lightscout.ui.core.R.color.glass_hero_border_low);
                break;
            case HIGH:
                glassPill(app.lightscout.ui.core.R.color.glass_range_pill_high_bg,
                        app.lightscout.ui.core.R.color.glass_range_pill_high_text,
                        app.lightscout.ui.core.R.color.glass_hero_border_high);
                break;
            case NONE:
                glassPill(app.lightscout.ui.core.R.color.glass_range_pill_in_range_bg,
                        app.lightscout.ui.core.R.color.glass_range_pill_in_range_text, 0);
                break;
            default:
                throw new IllegalArgumentException("Unknown range label: " + label);
        }
        rangeLabel.setVisibility(View.VISIBLE);
    }

    /** Glass range pill (mockup status-pill): tinted background behind the band text (light and dark); the label stays the cue. */
    private void glassPill(int backgroundRes, int textRes, int strokeRes) {
        // High / Low sit on a tinted hero: an opaque pill with a border in the band colour stays distinct from it.
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        shape.setCornerRadius(dp(100));
        shape.setColor(color(backgroundRes));
        if (strokeRes != 0) {
            shape.setStroke(dp(1), color(strokeRes));
        }
        rangeLabel.setBackground(shape);
        rangeLabel.setBackgroundTintList(null);
        rangeLabel.setTextColor(color(textRes));
        rangeLabel.setGravity(android.view.Gravity.CENTER);
    }

    private void renderProgress(HomeUiState state) {
        FreshnessPresentation p = FreshnessPresentation.from(state);
        nextReading.setPresentation(p);
        FreshnessPresentation.Phase meaningful = viewModel.model().takeFreshnessAnnouncement();
        if (meaningful != null) {
            if (meaningful == FreshnessPresentation.Phase.LOADING) {
                nextReading.announceForAccessibility(getString(R.string.s01_state_first_use));
            } else if (meaningful == FreshnessPresentation.Phase.STALE) {
                nextReading.announceForAccessibility(getString(app.lightscout.ui.core.R.string.s01_age_no_readings));
            } else {
                nextReading.announceForAccessibility(readingGroupDescription(state));
            }
        }
    }

    private CharSequence readingGroupDescription(HomeUiState s) {
        if (s.sgv() == null) {
            return getString(app.lightscout.ui.core.R.string.s01_age_no_readings) + ", "
                    + getString(R.string.s01_unit_mgdl) + ", " + deltaText(s);
        }
        int range = HomeCopy.rangeLabel(s.rangeLabel());
        return StatusA11y.compose(getResources().getConfiguration().getLocales().get(0),
                getString(R.string.s01_status_a11y), getString(R.string.s01_status_a11y_range),
                getString(R.string.s01_status_a11y_iob), s.sgv(), range == 0 ? null : getString(range),
                Formatters.trendWords(requireContext(), s.trend()),
                Formatters.readingAgeSpoken(requireContext(), s.age()), s.iob(), deltaText(s));
    }

    private String deltaText(HomeUiState s) {
        return s.deltaMgdl() == null ? getString(R.string.s01_delta_unknown)
                : getString(R.string.s01_delta, GlucoseDelta.signedText(s.deltaMgdl()));
    }

    private void configureHomeLayout(View view, HomeContentScrollView scroll) {
        float fontScale = getResources().getConfiguration().fontScale;
        boolean largeFont = HomeLayoutPolicy.allowsScrolling(fontScale);
        boolean wrapStatus = HomeLayoutPolicy.requiresWrapping(fontScale);
        scroll.setFillViewport(!largeFont);

        LinearLayout content = view.findViewById(R.id.s01_content);
        ViewGroup.LayoutParams contentParams = content.getLayoutParams();
        contentParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        content.setLayoutParams(contentParams);
        int padding = dp(largeFont ? 16 : 8);
        content.setPadding(padding, padding, padding, padding);

        LinearLayout valueRow = view.findViewById(R.id.s01_value_row);
        valueRow.setOrientation(wrapStatus ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        ViewGroup.LayoutParams valueRowParams = valueRow.getLayoutParams();
        LinearLayout.LayoutParams valueRowLinear = (LinearLayout.LayoutParams) valueRowParams;
        valueRowLinear.width = largeFont ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        valueRowLinear.weight = largeFont ? 0f : 1f;
        valueRow.setLayoutParams(valueRowLinear);
        ((LinearLayout) view.findViewById(R.id.s01_top_row))
                .setOrientation(largeFont ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        setStartMargin(iob, dp(wrapStatus ? 0 : 8));
        setStartMargin(delta, dp(wrapStatus ? 0 : 8));
        // Freshness bar shares the text inset so bar and text lines start and end on the same edges.
        ViewGroup.MarginLayoutParams trackParams = (ViewGroup.MarginLayoutParams) nextReading.getLayoutParams();
        trackParams.setMarginStart(dp(16));
        trackParams.setMarginEnd(dp(16));
        nextReading.setLayoutParams(trackParams);
        View dockPill = view.findViewById(R.id.s01_dock_pill);
        ViewGroup.MarginLayoutParams pillParams = (ViewGroup.MarginLayoutParams) dockPill.getLayoutParams();
        if (pillParams.getMarginStart() > 0) {
            pillParams.setMarginStart(dp(largeFont ? 16 : 8));
            pillParams.setMarginEnd(dp(largeFont ? 16 : 8));
            dockPill.setLayoutParams(pillParams);
        }
        TextView readingView = view.findViewById(R.id.s01_reading);
        ViewGroup.LayoutParams readingParams = readingView.getLayoutParams();
        readingParams.width = wrapStatus ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        readingView.setLayoutParams(readingParams);
        setStartMargin(view.findViewById(R.id.s01_unit), dp(wrapStatus ? 0 : 4));
        setStartMargin(view.findViewById(R.id.s01_trend), dp(wrapStatus ? 0 : 4));

        LinearLayout statusRow = view.findViewById(R.id.s01_status_row);
        statusRow.setOrientation(wrapStatus ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ageParams = (LinearLayout.LayoutParams) age.getLayoutParams();
        ageParams.width = wrapStatus ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        ageParams.weight = wrapStatus ? 0f : 1f;
        ageParams.setMarginStart(dp(wrapStatus ? 0 : 8));
        age.setLayoutParams(ageParams);

        HomeChartHost chartHost = view.findViewById(R.id.s01_chart_host);
        LinearLayout.LayoutParams chartParams = (LinearLayout.LayoutParams) chartHost.getLayoutParams();
        chartParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        chartParams.weight = largeFont ? 0f : 1f;
        chartParams.topMargin = dp(largeFont ? 16 : 8);
        chartHost.setLayoutParams(chartParams);
        chartHost.setMinimumHeight(dp(largeFont ? 256 : 160));

        setTopMargin(view.findViewById(R.id.s01_counters), dp(largeFont ? 12 : 8));
        setTopMargin(view.findViewById(R.id.s01_doughnut_card), dp(largeFont ? 12 : 8));
        readingGroup.setPadding(dp(16), readingGroup.getPaddingTop(),
                dp(16), readingGroup.getPaddingBottom());
    }

    private static void setTopMargin(View view, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.topMargin = margin;
        view.setLayoutParams(params);
    }

    private static void setStartMargin(View view, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.setMarginStart(margin);
        view.setLayoutParams(params);
    }

}
