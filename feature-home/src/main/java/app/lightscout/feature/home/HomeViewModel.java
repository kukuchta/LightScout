package app.lightscout.feature.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.statistics.PeriodFactory;
import app.lightscout.domain.statistics.Statistics;
import app.lightscout.domain.statistics.StatisticsCalculator;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.Segment;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S-01 ViewModel (CMP-70; CP-12 §4.1, §7.10; Q-88): exposes {@link HomeModel} as LiveData.
 *
 * <p>The IF-21 listener is registered in the constructor and removed in {@link #onCleared()} (CP-12 §7.10). Listener
 * calls arrive on {@code monitorExecutor} and are published with {@code postValue}, so the model only runs on the
 * main thread; the only main-thread status access is the memory read of {@code current()} (CP-00A R-25, R-34). UI
 * observation of {@link #state()} stays lifecycle-aware: a stopped screen keeps only the latest snapshot and renders
 * it when it starts again. The model lives as long as the ViewModel, so the loading period and pending action flags
 * survive configuration changes (GIR-2); nothing goes into saved state (CP-00 §6).
 */
public final class HomeViewModel extends ViewModel implements HomeModel.Listener {

    private final StatusRepository status;
    private final Clock clock;
    private final HomeModel model;
    private final HistoryLoader history;
    private final SettingsService settings;
    private final Executor ioExecutor;
    private final MutableLiveData<StatusSnapshot> snapshots = new MutableLiveData<>();
    private final MediatorLiveData<HomeUiState> state = new MediatorLiveData<>();
    private final MutableLiveData<OneShot<HomeCommand>> commands = new MutableLiveData<>();
    private final MutableLiveData<OneShot<Long>> chartResults = new MutableLiveData<>();
    private final MutableLiveData<StatisticsResult> statisticsResults = new MutableLiveData<>();
    private final MutableLiveData<DeltaResult> deltaResults = new MutableLiveData<>();
    private final StatusListener listener = snapshots::postValue;
    private final AtomicLong statisticsGeneration = new AtomicLong();
    private final AtomicLong chartGeneration = new AtomicLong();
    private final AtomicLong chartResultSequence = new AtomicLong();
    private final AtomicLong deltaGeneration = new AtomicLong();
    private final ConcurrentLinkedQueue<ChartResult> pendingChartResults = new ConcurrentLinkedQueue<>();
    private String lastStatisticsReadingId;
    private boolean hasRequestedInitialStatistics;
    private boolean chartCachePreloadStarted;
    private boolean chartCacheReadInFlight;
    private boolean chartCacheReadAgain;
    private Instant chartCacheFrom;
    private Instant chartCacheTo;
    private Segment preparedChartSegment;
    private FetchOutcome chartCacheOutcome;
    private String chartCacheDetail;
    private long chartServerEpoch;
    private long deltaServerEpoch = Long.MIN_VALUE;
    private DeltaKey requestedDeltaKey;
    private Instant lastChartSuccessfulCheck;
    private ChartViewport chartViewport;
    private ChartWindow activeChartWindow;
    private long activeChartGeneration = -1;
    private ChartWindow pendingChartWindow;

    /**
     * Creates the ViewModel, seeds it with the current snapshot (memory read, allowed on the main thread) and
     * registers the IF-21 listener for the ViewModel's lifetime.
     *
     * @param status IF-21
     * @param alarms IF-25
     * @param clock  IF-15
     * @param timer  main-thread delays for the GIR-1 fail-safes
     */
    public HomeViewModel(StatusRepository status, AlarmActions alarms, Clock clock, HomeModel.Timer timer) {
        this(status, alarms, clock, timer, null, null, Runnable::run);
    }

    /**
     * Creates the complete S-01 ViewModel with IF-22 and IF-26 for chart and doughnut loading.
     *
     * @param status IF-21
     * @param alarms IF-25
     * @param clock IF-15
     * @param timer main-thread delays
     * @param history IF-22
     * @param settings IF-26
     * @param ioExecutor shared off-main executor for blocking IF-26 reads
     */
    public HomeViewModel(StatusRepository status, AlarmActions alarms, Clock clock, HomeModel.Timer timer,
                         HistoryLoader history, SettingsService settings, Executor ioExecutor) {
        this.status = status;
        this.clock = clock;
        this.history = history;
        this.settings = settings;
        this.ioExecutor = ioExecutor;
        chartServerEpoch = history == null ? 0L : history.serverEpoch();
        model = new HomeModel(status, alarms, clock, timer);
        model.setListener(this);
        StatusSnapshot current = status.current();
        lastChartSuccessfulCheck = current.lastSuccessfulCheck();
        model.onSnapshot(current);
        snapshots.setValue(current);
        state.addSource(snapshots, s -> {
            if (s != null) {
                model.onSnapshot(s);
                requestDelta(s);
                String latestId = s.latest() == null ? null : s.latest().id();
                if (!hasRequestedInitialStatistics || !java.util.Objects.equals(latestId,
                        lastStatisticsReadingId)) {
                    hasRequestedInitialStatistics = true;
                    lastStatisticsReadingId = latestId;
                    loadDoughnut();
                }
                if (!java.util.Objects.equals(lastChartSuccessfulCheck, s.lastSuccessfulCheck())) {
                    lastChartSuccessfulCheck = s.lastSuccessfulCheck();
                    refreshChartCache();
                }
            }
        });
        state.addSource(statisticsResults, result -> {
            if (result != null) {
                if (result.loading()) {
                    model.onStatisticsLoading();
                } else if (result.available()) {
                    model.onStatistics(result.statistics());
                } else {
                    model.onStatisticsUnavailable();
                }
            }
        });
        state.addSource(deltaResults, result -> {
            if (result != null && isDeltaResultCurrent(result)) {
                model.onDelta(result.key().id(), result.key().time(), result.key().sgv(), result.deltaMgdl());
            }
        });
        status.addListener(listener);
    }

    /**
     * Returns the display state (observe with the view lifecycle).
     *
     * @return LiveData of the state
     */
    public LiveData<HomeUiState> state() {
        return state;
    }

    /**
     * Returns the one-time navigation commands.
     *
     * @return LiveData of the commands
     */
    public LiveData<OneShot<HomeCommand>> commands() {
        return commands;
    }

    /** One-shot signal for the queue of chart results; queueing avoids dropping concurrent cache/network results. */
    public LiveData<OneShot<Long>> chartResults() {
        return chartResults;
    }

    /** Drains chart results on the main thread. */
    public List<ChartResult> takeChartResults() {
        ArrayList<ChartResult> results = new ArrayList<>();
        ChartResult result;
        while ((result = pendingChartResults.poll()) != null) {
            if (isChartGenerationCurrent(result.generation())) {
                results.add(result);
            }
        }
        return List.copyOf(results);
    }

    /** Tells whether a chart callback still belongs to this ViewModel's active lifecycle. */
    public boolean isChartGenerationCurrent(long generation) {
        return chartGeneration.get() == generation;
    }

    /**
     * Returns the model for user actions and the minute refresh.
     *
     * @return the model
     */
    public HomeModel model() {
        return model;
    }

    /** Returns the ViewModel-only chart position, initialized at now / 6 h. */
    public ChartViewport chartViewport(Instant now) {
        if (chartViewport == null) {
            chartViewport = ChartViewportRules.defaultViewport(now);
        } else {
            chartViewport = ChartViewportRules.clamp(chartViewport, now);
        }
        return chartViewport;
    }

    /** Keeps the S-01 chart position across view recreation and rotation (not process death). */
    public void onViewportChanged(ChartViewport viewport) {
        chartViewport = viewport;
    }

    /**
     * S-01 resume refreshes readiness through IF-21 and recomputes the last-24-hour summary. A chart reset is
     * triggered only when IF-22 reports a new server epoch, never for a view recreation or in-app return.
     *
     * @return whether the chart cache belongs to a different server and must be cleared
     */
    public boolean onResume() {
        model.onResume();
        loadDoughnut();
        long currentEpoch = history == null ? 0L : history.serverEpoch();
        synchronized (this) {
            if (chartServerEpoch == currentEpoch) {
                return false;
            }
            chartServerEpoch = currentEpoch;
            chartGeneration.incrementAndGet();
            activeChartWindow = null;
            activeChartGeneration = -1;
            pendingChartWindow = null;
            chartCachePreloadStarted = false;
            preparedChartSegment = null;
            chartCacheOutcome = null;
            chartCacheDetail = null;
            chartCacheFrom = null;
            chartCacheTo = null;
            chartCacheReadAgain = chartCacheReadInFlight;
            deltaGeneration.incrementAndGet();
            requestedDeltaKey = null;
            deltaServerEpoch = Long.MIN_VALUE;
        }
        model.invalidateDelta();
        requestDelta(status.current());
        return true;
    }

    private void requestDelta(StatusSnapshot snapshot) {
        if (history == null) {
            return;
        }
        Reading latest = snapshot.latest();
        DeltaKey key = latest == null ? null : new DeltaKey(latest.id(), latest.time(), latest.sgv());
        long serverEpoch = history.serverEpoch();
        long generation;
        boolean cacheReset;
        synchronized (this) {
            if (serverEpoch == deltaServerEpoch && java.util.Objects.equals(key, requestedDeltaKey)) {
                return;
            }
            cacheReset = deltaServerEpoch != Long.MIN_VALUE && serverEpoch != deltaServerEpoch;
            deltaServerEpoch = serverEpoch;
            requestedDeltaKey = key;
            generation = deltaGeneration.incrementAndGet();
        }
        if (cacheReset) {
            model.invalidateDelta();
        }
        if (latest == null || latest.time() == null) {
            return;
        }
        Instant to = latest.time();
        Instant from = to.minus(Duration.ofMinutes(6));
        history.loadCachedSegment(from, to, result -> {
            Integer delta = result.ok() && result.value() != null
                    ? GlucoseDelta.calculate(latest, result.value().readings()) : null;
            synchronized (this) {
                if (deltaGeneration.get() != generation || !java.util.Objects.equals(requestedDeltaKey, key)
                        || history.serverEpoch() != serverEpoch) {
                    return;
                }
            }
            deltaResults.postValue(new DeltaResult(generation, key, serverEpoch, delta));
        });
    }

    private synchronized boolean isDeltaResultCurrent(DeltaResult result) {
        return result.generation() == deltaGeneration.get()
                && java.util.Objects.equals(result.key(), requestedDeltaKey)
                && history != null && history.serverEpoch() == result.serverEpoch();
    }

    /** Reconciles the displayed cache snapshot after an in-app entry write changes ST-01. */
    public void onChartDataChanged() {
        refreshChartCache();
    }

    /**
     * Returns the last completed cache snapshot for synchronous seeding of a recreated chart view, or {@code null}
     * while its first read is pending.
     */
    public ChartResult preparedChartCache() {
        synchronized (this) {
            if (!chartCachePreloadStarted || preparedChartSegment == null && chartCacheOutcome == null) {
                return null;
            }
            return new ChartResult(chartCacheFrom, chartCacheTo, preparedChartSegment, true,
                    chartCacheOutcome, chartCacheDetail, chartGeneration.get());
        }
    }

    /**
     * Starts one cache-only seven-day read for this ViewModel. Its fixed range is retained through snapshot,
     * viewport and view recreation updates; it never consults coverage metadata or triggers a network request.
     */
    public void prepareChartCache() {
        if (history == null) {
            return;
        }
        Instant from = null;
        Instant to = null;
        synchronized (this) {
            if (chartCachePreloadStarted) {
                return;
            } else {
                chartCachePreloadStarted = true;
                chartCacheTo = clock.now();
                chartCacheFrom = chartCacheTo.minus(Duration.ofDays(7));
                from = chartCacheFrom;
                to = chartCacheTo;
            }
        }
        if (from != null) {
            startChartCacheRead(from, to, chartGeneration.get());
        }
    }

    private void refreshChartCache() {
        Instant from;
        Instant to;
        synchronized (this) {
            if (!chartCachePreloadStarted) {
                return;
            }
            if (activeChartWindow != null) {
                chartCacheReadAgain = true;
                return;
            }
            from = chartCacheFrom;
            to = chartCacheTo;
        }
        startChartCacheRead(from, to, chartGeneration.get());
    }

    private void startChartCacheRead(Instant from, Instant to, long generation) {
        synchronized (this) {
            if (chartCacheReadInFlight) {
                chartCacheReadAgain = true;
                return;
            }
            chartCacheReadInFlight = true;
        }
        history.loadCachedSegment(from, to, result -> {
            boolean publish;
            boolean repeat;
            boolean defer;
            long nextGeneration;
            Instant nextFrom;
            Instant nextTo;
            synchronized (this) {
                chartCacheReadInFlight = false;
                defer = activeChartWindow != null;
                repeat = chartCacheReadAgain || defer;
                publish = isChartGenerationCurrent(generation) && !repeat;
                if (publish) {
                    if (result.ok()) {
                        preparedChartSegment = result.value();
                    }
                    chartCacheOutcome = result.outcome();
                    chartCacheDetail = result.detailCode();
                }
                if (defer) {
                    chartCacheReadAgain = true;
                } else if (repeat) {
                    chartCacheReadAgain = false;
                }
                nextGeneration = chartGeneration.get();
                nextFrom = chartCacheFrom;
                nextTo = chartCacheTo;
            }
            if (publish) {
                publishChartResult(new ChartResult(from, to, result.ok() ? result.value() : null, true,
                        result.outcome(), result.detailCode(), generation));
            }
            if (repeat && !defer && nextFrom != null && isChartGenerationCurrent(nextGeneration)) {
                startChartCacheRead(nextFrom, nextTo, nextGeneration);
            }
        });
    }

    /** Loads one chart window through IF-22; callback data is delivered to the view on the main thread. */
    public void loadSegment(Instant from, Instant to) {
        if (history == null) {
            return;
        }
        long generation = chartGeneration.get();
        ChartWindow requested = new ChartWindow(from, to);
        synchronized (this) {
            if (activeChartWindow != null) {
                if (activeChartGeneration == generation && activeChartWindow.contains(requested)) {
                    pendingChartWindow = null;
                    return;
                }
                if (!requested.equals(pendingChartWindow)) {
                    pendingChartWindow = requested;
                }
                return;
            }
            activeChartWindow = requested;
            activeChartGeneration = generation;
            if (chartCacheReadInFlight) {
                chartCacheReadAgain = true;
            }
        }
        launchChartLoad(requested, generation);
    }

    private void launchChartLoad(ChartWindow window, long generation) {
        history.loadSegment(window.from(), window.to(), result -> {
            if (isChartGenerationCurrent(generation)) {
                publishChartResult(new ChartResult(window.from(), window.to(),
                        result.ok() ? result.value() : null, false, result.outcome(), result.detailCode(), generation));
            }
            ChartWindow next;
            long nextGeneration;
            Instant cacheFrom = null;
            Instant cacheTo = null;
            boolean startCache = false;
            synchronized (this) {
                if (!window.equals(activeChartWindow) || activeChartGeneration != generation) {
                    return;
                }
                activeChartWindow = null;
                next = pendingChartWindow;
                pendingChartWindow = null;
                if (next != null) {
                    activeChartWindow = next;
                    activeChartGeneration = chartGeneration.get();
                    if (chartCacheReadInFlight) {
                        chartCacheReadAgain = true;
                    }
                } else if (chartCacheReadAgain && !chartCacheReadInFlight && chartCachePreloadStarted) {
                    chartCacheReadAgain = false;
                    cacheFrom = chartCacheFrom;
                    cacheTo = chartCacheTo;
                    startCache = cacheFrom != null;
                }
                nextGeneration = chartGeneration.get();
            }
            if (next != null) {
                launchChartLoad(next, nextGeneration);
            } else if (startCache) {
                startChartCacheRead(cacheFrom, cacheTo, nextGeneration);
            }
        });
    }

    private void publishChartResult(ChartResult result) {
        pendingChartResults.add(result);
        chartResults.postValue(new OneShot<>(chartResultSequence.incrementAndGet()));
    }

    private void loadDoughnut() {
        if (history == null || settings == null) {
            return;
        }
        long generation = statisticsGeneration.incrementAndGet();
        statisticsResults.setValue(new StatisticsResult(null, true, false));
        Instant now = clock.now();
        var period = PeriodFactory.lastDays(1, now);
        ioExecutor.execute(() -> {
            final Settings currentSettings;
            try {
                currentSettings = settings.get();
            } catch (RuntimeException e) {
                if (statisticsGeneration.get() == generation) {
                    statisticsResults.postValue(new StatisticsResult(null, false, false));
                }
                return;
            }
            history.loadPeriod(period, result -> {
                if (statisticsGeneration.get() != generation) {
                    return;
                }
                if (!result.ok() || result.value() == null) {
                    statisticsResults.postValue(new StatisticsResult(null, false, false));
                    return;
                }
                Statistics computed = new StatisticsCalculator()
                        .compute(result.value(), period, currentSettings.targetRange());
                statisticsResults.postValue(new StatisticsResult(computed, false, true));
            });
        });
    }

    @Override
    public void onState(HomeUiState s) {
        state.setValue(s);
    }

    @Override
    public void onCommand(HomeCommand command) {
        commands.setValue(new OneShot<>(command));
    }

    @Override
    protected void onCleared() {
        chartGeneration.incrementAndGet();
        deltaGeneration.incrementAndGet();
        synchronized (this) {
            pendingChartWindow = null;
            chartCacheReadAgain = false;
        }
        status.removeListener(listener);
        model.dispose();
        model.setListener(null);
    }

    /** Window result used by the chart view. */
    public record ChartResult(Instant from, Instant to, Segment segment, boolean cacheOnly,
                              FetchOutcome outcome, String detailCode, long generation) {
        public boolean success() {
            return segment != null && outcome == FetchOutcome.OK;
        }
    }

    private record ChartWindow(Instant from, Instant to) {
        boolean contains(ChartWindow other) {
            return !from.isAfter(other.from) && !to.isBefore(other.to);
        }
    }

    private record StatisticsResult(Statistics statistics, boolean loading, boolean available) {
    }

    private record DeltaKey(String id, Instant time, int sgv) {
    }

    private record DeltaResult(long generation, DeltaKey key, long serverEpoch, Integer deltaMgdl) {
    }
}
