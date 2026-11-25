package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.feature.home.Fakes.FakeAlarmActions;
import app.lightscout.feature.home.Fakes.FakeClock;
import app.lightscout.feature.home.Fakes.FakeStatusRepository;
import app.lightscout.feature.home.Fakes.Snap;
import app.lightscout.feature.home.HomeUiState.BannerKind;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.Segment;
import app.lightscout.usecases.SettingsService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Q-88 / CP-12 §7.10: the IF-21 listener is registered by the ViewModel for its whole lifetime (also with no UI
 * observer) and removed in {@code onCleared()}; listener calls reach the model. LiveData runs on the JVM with a
 * synchronous task executor standing in for the main looper (Robolectric is not allowed — CP-00 §2).
 */
public class HomeViewModelTest {

    private FakeStatusRepository status;
    private HomeViewModel viewModel;

    @Before
    public void setUp() {
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override
            public void executeOnDiskIO(Runnable runnable) {
                runnable.run();
            }

            @Override
            public void postToMainThread(Runnable runnable) {
                runnable.run();
            }

            @Override
            public boolean isMainThread() {
                return true;
            }
        });
        status = new FakeStatusRepository(new Snap().build());
        viewModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(), new Fakes.FakeTimer());
    }

    @After
    public void tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null);
    }

    @Test
    public void listenerRegisteredAtCreationWithoutObservers() {
        assertEquals(1, status.listeners.size());
        assertTrue(viewModel.model().state() != null);
        assertNull(viewModel.model().state().banner());
    }

    @Test
    public void listenerRemovedOnCleared() {
        StatusListener registered = status.listeners.get(0);
        viewModel.onCleared();
        assertTrue(status.listeners.isEmpty());
        // A late call from monitorExecutor after clearing does not throw.
        Snap s = new Snap();
        s.readinessMissing = 1;
        registered.onStatus(s.build());
    }

    @Test
    public void listenerCallReachesModelWhileObserved() {
        viewModel.state().observeForever(st -> { });
        Snap s = new Snap();
        s.readinessMissing = 1;
        status.listeners.get(0).onStatus(s.build());
        assertEquals(BannerKind.PERMISSION_MISSING, viewModel.model().state().banner().kind());
        assertEquals(BannerKind.PERMISSION_MISSING, viewModel.state().getValue().banner().kind());
    }

    @Test
    public void listenerStaysRegisteredWhenObserverIsStopped() {
        LifecycleOwner owner = new LifecycleOwner() {
            private final LifecycleRegistry registry = LifecycleRegistry.createUnsafe(this);

            @Override
            public Lifecycle getLifecycle() {
                return registry;
            }
        };
        LifecycleRegistry registry = (LifecycleRegistry) owner.getLifecycle();
        registry.setCurrentState(Lifecycle.State.RESUMED);
        java.util.List<HomeUiState> rendered = new java.util.ArrayList<>();
        viewModel.state().observe(owner, rendered::add);
        assertEquals(1, status.listeners.size());
        registry.setCurrentState(Lifecycle.State.CREATED);   // owner STOPPED
        assertEquals("IR-105 / Q-88: still registered while stopped", 1, status.listeners.size());
        int before = rendered.size();
        Snap s = new Snap();
        s.alarmsEnabled = false;
        status.listeners.get(0).onStatus(s.build());
        assertEquals("a stopped owner is not called", before, rendered.size());
        registry.setCurrentState(Lifecycle.State.STARTED);
        assertEquals(BannerKind.ALARMS_OFF, rendered.get(rendered.size() - 1).banner().kind());
    }

    @Test
    public void snapshotsWhileStoppedAreKeptAndRenderedOnStart() {
        Snap s = new Snap();
        s.alarmsEnabled = false;
        status.listeners.get(0).onStatus(s.build());
        viewModel.state().observeForever(st -> { });
        assertEquals(BannerKind.ALARMS_OFF, viewModel.state().getValue().banner().kind());
    }

    @Test
    public void doughnutLoadsHistoryAndUsesSettingsOffTheMainExecutionPath() {
        List<Runnable> ioTasks = new ArrayList<>();
        AtomicInteger settingsReads = new AtomicInteger();
        AtomicInteger periodLoads = new AtomicInteger();
        Settings configured = new Settings(true, true, 70, 55, true, 250, 20,
                15, 15, 60, 30, 3, 10, new TargetRange(110, 130));
        SettingsService settings = new SettingsService() {
            @Override
            public Settings get() {
                settingsReads.incrementAndGet();
                return configured;
            }

            @Override
            public app.lightscout.domain.entry.ValidationResult update(
                    app.lightscout.domain.settings.SettingChange change) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void addListener(java.util.function.Consumer<Settings> listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void removeListener(java.util.function.Consumer<Settings> listener) {
                throw new UnsupportedOperationException();
            }
        };
        HistoryLoader history = new HistoryLoader() {
            @Override
            public void loadSegment(Instant from, Instant to, Callback<Segment> callback) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void loadCachedSegment(Instant from, Instant to, Callback<Segment> callback) {
                callback.onResult(Result.ok(new Segment(from, to, List.of(), List.of(), List.of())));
            }

            @Override
            public void loadPeriod(Period period, Callback<List<Reading>> callback) {
                periodLoads.incrementAndGet();
                List<Reading> readings = new ArrayList<>();
                for (int i = 0; i < 202; i++) {
                    readings.add(new Reading("history-" + i, Fakes.T0.minus(Duration.ofMinutes(i * 5L + 1)),
                            100, Trend.FLAT, "test"));
                }
                callback.onResult(Result.ok(readings));
            }

            @Override
            public List<Reading> cachedReadings(Instant from, Instant to) {
                throw new UnsupportedOperationException();
            }
        };
        HomeViewModel complete = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, settings, ioTasks::add);
        List<HomeUiState> rendered = new ArrayList<>();
        complete.state().observeForever(rendered::add);

        assertEquals(0, settingsReads.get());
        assertEquals(1, ioTasks.size());
        ioTasks.remove(0).run();

        HomeUiState finalState = rendered.get(rendered.size() - 1);
        assertEquals(1, settingsReads.get());
        assertEquals(1, periodLoads.get());
        assertTrue(finalState.statisticsLoaded());
        assertEquals(100d, finalState.statistics().tbrPct(), 0d);
        assertEquals(0d, finalState.statistics().tirPct(), 0d);
        complete.onCleared();
    }

    @Test
    public void chartCachePreparationKeepsOneFixedSevenDayRangeAcrossViewportAndSnapshotRefresh() {
        CacheHistory history = new CacheHistory();
        HomeViewModel chartModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        chartModel.prepareChartCache();
        chartModel.prepareChartCache();
        assertEquals(1, history.cacheLoads);
        Instant fixedFrom = Fakes.T0.minus(Duration.ofDays(7));
        Instant fixedTo = Fakes.T0;
        assertEquals(fixedFrom, history.cacheFrom.get());
        assertEquals(fixedTo, history.cacheTo.get());

        chartModel.onViewportChanged(new ChartViewport(Fakes.T0.minus(Duration.ofDays(2)), Duration.ofHours(12)));
        chartModel.loadSegment(Fakes.T0.minus(Duration.ofDays(2)).minus(Duration.ofHours(12)),
                Fakes.T0.minus(Duration.ofDays(2)));
        Snap refreshed = new Snap();
        refreshed.alarmsEnabled = false;
        status.listeners.get(0).onStatus(refreshed.build());
        chartModel.prepareChartCache();

        assertEquals(1, history.cacheLoads);
        assertEquals(fixedFrom, history.cacheFrom.get());
        assertEquals(fixedTo, history.cacheTo.get());
        assertEquals(1, history.visibleLoads);

        history.visibleCallbacks.get(0).onResult(Result.ok(
                new Segment(fixedFrom, fixedTo, List.of(), List.of(), List.of())));
        Segment prepared = new Segment(fixedFrom, fixedTo, List.of(), List.of(), List.of());
        history.cacheCallbacks.get(0).onResult(Result.ok(prepared));
        assertEquals(2, history.cacheLoads);
        history.cacheCallbacks.get(1).onResult(Result.ok(prepared));
        chartModel.prepareChartCache();
        List<HomeViewModel.ChartResult> results = chartModel.takeChartResults();
        assertEquals(2, results.size());
        assertTrue(!results.get(0).cacheOnly());
        assertTrue(results.get(1).cacheOnly());
        assertEquals(fixedFrom, results.get(1).from());
        assertEquals(fixedTo, results.get(1).to());
        assertEquals(prepared, chartModel.preparedChartCache().segment());
        chartModel.onCleared();
    }

    @Test
    public void deltaUsesOnlyCurrentSnapshotAndIgnoresLateOlderCacheResults() {
        CacheHistory history = new CacheHistory();
        Reading firstReading = new Reading("first", Fakes.T0.minus(Duration.ofMinutes(2)), 120,
                Trend.FLAT, "synthetic");
        status.current = new Snap().latest(firstReading).build();
        HomeViewModel deltaModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        deltaModel.state().observeForever(state -> { });
        assertEquals(1, history.deltaLoads);
        assertEquals(firstReading.time().minus(Duration.ofMinutes(6)), history.deltaFrom.get(0));
        assertEquals(firstReading.time(), history.deltaTo.get(0));
        assertNull(deltaModel.state().getValue().deltaMgdl());

        Reading latest = new Reading("latest", Fakes.T0.minus(Duration.ofMinutes(1)), 145,
                Trend.FLAT, "synthetic");
        StatusSnapshot nextSnapshot = new Snap().latest(latest).build();
        status.current = nextSnapshot;
        status.listeners.get(1).onStatus(nextSnapshot);
        assertEquals(2, history.deltaLoads);
        assertNull(deltaModel.state().getValue().deltaMgdl());

        history.deltaCallbacks.get(0).onResult(Result.ok(segment(firstReading.time(),
                List.of(new Reading("first-prev", firstReading.time().minus(Duration.ofMinutes(5)),
                        100, Trend.FLAT, "synthetic")))));
        assertNull(deltaModel.state().getValue().deltaMgdl());

        history.deltaCallbacks.get(1).onResult(Result.ok(segment(latest.time(),
                List.of(new Reading("latest-prev", latest.time().minus(Duration.ofMinutes(5)),
                        110, Trend.FLAT, "synthetic")))));
        assertEquals(Integer.valueOf(35), deltaModel.state().getValue().deltaMgdl());
        deltaModel.onCleared();
    }

    @Test
    public void cacheEpochResetClearsDeltaAndRequiresFreshResult() {
        CacheHistory history = new CacheHistory();
        Reading latest = new Reading("latest", Fakes.T0.minus(Duration.ofMinutes(1)), 145,
                Trend.FLAT, "synthetic");
        status.current = new Snap().latest(latest).build();
        HomeViewModel deltaModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        deltaModel.state().observeForever(state -> { });
        history.deltaCallbacks.get(0).onResult(Result.ok(segment(latest.time(),
                List.of(new Reading("prev", latest.time().minus(Duration.ofMinutes(5)),
                        110, Trend.FLAT, "synthetic")))));
        assertEquals(Integer.valueOf(35), deltaModel.state().getValue().deltaMgdl());

        history.serverEpoch = 1L;
        assertTrue(deltaModel.onResume());
        assertNull(deltaModel.state().getValue().deltaMgdl());
        assertEquals(2, history.deltaLoads);
        history.deltaCallbacks.get(0).onResult(Result.ok(segment(latest.time(),
                List.of(new Reading("stale-prev", latest.time().minus(Duration.ofMinutes(5)),
                        100, Trend.FLAT, "synthetic")))));
        assertNull(deltaModel.state().getValue().deltaMgdl());
        history.deltaCallbacks.get(1).onResult(Result.ok(segment(latest.time(),
                List.of(new Reading("fresh-prev", latest.time().minus(Duration.ofMinutes(5)),
                        130, Trend.FLAT, "synthetic")))));
        assertEquals(Integer.valueOf(15), deltaModel.state().getValue().deltaMgdl());
        deltaModel.onCleared();
    }

    @Test
    public void queuedDeltaIsRevalidatedAgainstServerEpochWhenApplied() {
        CacheHistory history = new CacheHistory();
        Reading latest = new Reading("latest", Fakes.T0.minus(Duration.ofMinutes(1)), 145,
                Trend.FLAT, "synthetic");
        status.current = new Snap().latest(latest).build();
        HomeViewModel deltaModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        deltaModel.state().observeForever(state -> { });
        List<Runnable> mainQueue = new ArrayList<>();
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override
            public void executeOnDiskIO(Runnable runnable) {
                runnable.run();
            }

            @Override
            public void postToMainThread(Runnable runnable) {
                mainQueue.add(runnable);
            }

            @Override
            public boolean isMainThread() {
                return true;
            }
        });
        try {
            history.deltaCallbacks.get(0).onResult(Result.ok(segment(latest.time(),
                    List.of(new Reading("prev", latest.time().minus(Duration.ofMinutes(5)),
                            110, Trend.FLAT, "synthetic")))));
            assertEquals(1, mainQueue.size());

            history.serverEpoch = 1L;
            mainQueue.remove(0).run();
            assertNull("queued result from the prior server epoch must not be applied",
                    deltaModel.state().getValue().deltaMgdl());

            assertTrue("resume starts a fresh request after the epoch change", deltaModel.onResume());
            history.deltaCallbacks.get(1).onResult(Result.ok(segment(latest.time(),
                    List.of(new Reading("fresh-prev", latest.time().minus(Duration.ofMinutes(5)),
                            105, Trend.FLAT, "synthetic")))));
            assertEquals(1, mainQueue.size());
            mainQueue.remove(0).run();
            assertEquals(Integer.valueOf(40), deltaModel.state().getValue().deltaMgdl());
        } finally {
            deltaModel.onCleared();
            ArchTaskExecutor.getInstance().setDelegate(null);
        }
    }

    @Test
    public void staleCurrentReadingCanRetainItsHistoricalDelta() {
        CacheHistory history = new CacheHistory();
        Reading latest = new Reading("stale", Fakes.T0.minus(Duration.ofMinutes(30)), 145,
                Trend.FLAT, "synthetic");
        status.current = new Snap().latest(latest).build();
        HomeViewModel deltaModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        deltaModel.state().observeForever(state -> { });
        history.deltaCallbacks.get(0).onResult(Result.ok(segment(latest.time(),
                List.of(new Reading("prev", latest.time().minus(Duration.ofMinutes(5)),
                        110, Trend.FLAT, "synthetic")))));
        assertTrue(deltaModel.state().getValue().stale());
        assertEquals(Integer.valueOf(35), deltaModel.state().getValue().deltaMgdl());
        deltaModel.onCleared();
    }

    @Test
    public void chartCacheCallbackAfterViewModelClearsIsDiscarded() {
        CacheHistory history = new CacheHistory();
        HomeViewModel chartModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        List<OneShot<Long>> signals = new ArrayList<>();
        chartModel.chartResults().observeForever(signals::add);
        chartModel.prepareChartCache();
        chartModel.onCleared();

        history.cacheCallbacks.get(0).onResult(Result.ok(new Segment(history.cacheFrom.get(), history.cacheTo.get(),
                List.of(), List.of(), List.of())));

        assertTrue(signals.isEmpty());
        assertTrue(chartModel.takeChartResults().isEmpty());
    }

    @Test
    public void viewOnlyResumeRetainsPreparedHistoryWithoutReloadingCache() {
        CacheHistory history = new CacheHistory();
        HomeViewModel chartModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        chartModel.prepareChartCache();
        Segment prepared = new Segment(history.cacheFrom.get(), history.cacheTo.get(),
                List.of(), List.of(), List.of());
        history.cacheCallbacks.get(0).onResult(Result.ok(prepared));
        HomeViewModel.ChartResult initial = chartModel.preparedChartCache();
        assertEquals(1, history.cacheLoads);
        chartModel.takeChartResults();

        assertTrue(!chartModel.onResume());
        assertTrue(!chartModel.onResume());
        chartModel.prepareChartCache();
        HomeViewModel.ChartResult afterResume = chartModel.preparedChartCache();
        assertEquals(1, history.cacheLoads);
        assertEquals(initial.generation(), afterResume.generation());
        assertEquals(prepared, afterResume.segment());
        assertTrue(chartModel.takeChartResults().isEmpty());
        chartModel.onCleared();
    }

    @Test
    public void successfulUnderlyingCheckRefreshesThePreparedCacheSnapshot() {
        CacheHistory history = new CacheHistory();
        HomeViewModel chartModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        chartModel.state().observeForever(state -> { });
        chartModel.prepareChartCache();
        Segment initial = new Segment(history.cacheFrom.get(), history.cacheTo.get(),
                List.of(), List.of(), List.of());
        history.cacheCallbacks.get(0).onResult(Result.ok(initial));
        chartModel.takeChartResults();

        Snap updated = new Snap();
        updated.lastCheckCompleted = Fakes.T0;
        status.listeners.get(status.listeners.size() - 1).onStatus(updated.build());

        assertEquals(2, history.cacheLoads);
        Segment refreshed = new Segment(history.cacheFrom.get(), history.cacheTo.get(),
                List.of(), List.of(), List.of());
        history.cacheCallbacks.get(1).onResult(Result.ok(refreshed));
        assertEquals(refreshed, chartModel.preparedChartCache().segment());
        chartModel.onChartDataChanged();
        history.cacheCallbacks.get(2).onResult(Result.fail(FetchOutcome.STORAGE_ERROR, "STORAGE"));
        assertEquals(refreshed, chartModel.preparedChartCache().segment());
        assertEquals(FetchOutcome.STORAGE_ERROR, chartModel.preparedChartCache().outcome());
        chartModel.onCleared();
    }

    @Test
    public void serverEpochResetInvalidatesOldVisibleCallbackAndClearsPreparedData() {
        CacheHistory history = new CacheHistory();
        HomeViewModel chartModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        chartModel.prepareChartCache();
        Segment oldServer = new Segment(history.cacheFrom.get(), history.cacheTo.get(),
                List.of(new Reading("old-server-reading", Fakes.T0.minus(Duration.ofHours(1)),
                        100, Trend.FLAT, "test")), List.of(), List.of());
        history.cacheCallbacks.get(0).onResult(Result.ok(oldServer));
        chartModel.takeChartResults();

        Instant from = Fakes.T0.minus(Duration.ofHours(6));
        chartModel.loadSegment(from, Fakes.T0);
        Callback<Segment> obsolete = history.visibleCallbacks.get(0);

        history.serverEpoch = 1L;
        assertTrue(chartModel.onResume());
        assertNull(chartModel.preparedChartCache());
        chartModel.prepareChartCache();
        assertEquals(2, history.cacheLoads);

        obsolete.onResult(Result.ok(new Segment(from, Fakes.T0, List.of(), List.of(), List.of())));
        assertTrue(chartModel.takeChartResults().isEmpty());
        Segment newServer = new Segment(history.cacheFrom.get(), history.cacheTo.get(),
                List.of(new Reading("new-server-reading", Fakes.T0.minus(Duration.ofHours(1)),
                        120, Trend.FLAT, "test")), List.of(), List.of());
        history.cacheCallbacks.get(1).onResult(Result.ok(newServer));
        assertEquals(newServer, chartModel.preparedChartCache().segment());
        assertEquals(List.of("new-server-reading"), chartModel.preparedChartCache().segment().readings().stream()
                .map(Reading::id).toList());
        assertEquals(1, chartModel.takeChartResults().size());
        chartModel.onCleared();
    }

    @Test
    public void overlappingChartRefreshesAreSerializedAndOnlyLatestViewportIsQueued() {
        CacheHistory history = new CacheHistory();
        HomeViewModel chartModel = new HomeViewModel(status, new FakeAlarmActions(), new FakeClock(),
                new Fakes.FakeTimer(), history, null, Runnable::run);
        Instant firstFrom = Fakes.T0.minus(Duration.ofHours(6));
        Instant firstTo = Fakes.T0;
        Instant secondFrom = firstFrom.minus(Duration.ofHours(1));
        Instant secondTo = firstTo.minus(Duration.ofHours(1));
        Instant latestFrom = secondFrom.minus(Duration.ofHours(1));
        Instant latestTo = secondTo.minus(Duration.ofHours(1));

        chartModel.loadSegment(firstFrom, firstTo);
        chartModel.loadSegment(secondFrom, secondTo);
        chartModel.loadSegment(latestFrom, latestTo);
        assertEquals(1, history.visibleLoads);

        history.visibleCallbacks.get(0).onResult(Result.ok(
                new Segment(firstFrom, firstTo, List.of(), List.of(), List.of())));

        assertEquals(2, history.visibleLoads);
        assertEquals(latestFrom, history.visibleFrom.get(1));
        assertEquals(latestTo, history.visibleTo.get(1));
        chartModel.onCleared();
    }

    private static final class CacheHistory implements HistoryLoader {
        int cacheLoads;
        int deltaLoads;
        int visibleLoads;
        long serverEpoch;
        final List<Instant> visibleFrom = new ArrayList<>();
        final List<Instant> visibleTo = new ArrayList<>();
        final List<Callback<Segment>> visibleCallbacks = new ArrayList<>();
        final List<Instant> requestedCacheFrom = new ArrayList<>();
        final List<Instant> requestedCacheTo = new ArrayList<>();
        final List<Callback<Segment>> cacheCallbacks = new ArrayList<>();
        final List<Instant> deltaFrom = new ArrayList<>();
        final List<Instant> deltaTo = new ArrayList<>();
        final List<Callback<Segment>> deltaCallbacks = new ArrayList<>();
        final AtomicReference<Instant> cacheFrom = new AtomicReference<>();
        final AtomicReference<Instant> cacheTo = new AtomicReference<>();

        @Override
        public void loadSegment(Instant from, Instant to, Callback<Segment> callback) {
            visibleLoads++;
            visibleFrom.add(from);
            visibleTo.add(to);
            visibleCallbacks.add(callback);
        }

        @Override
        public void loadCachedSegment(Instant from, Instant to, Callback<Segment> callback) {
            if (Duration.between(from, to).equals(Duration.ofMinutes(6))) {
                deltaLoads++;
                deltaFrom.add(from);
                deltaTo.add(to);
                deltaCallbacks.add(callback);
                return;
            }
            cacheLoads++;
            cacheFrom.set(from);
            cacheTo.set(to);
            requestedCacheFrom.add(from);
            requestedCacheTo.add(to);
            cacheCallbacks.add(callback);
        }

        @Override
        public long serverEpoch() {
            return serverEpoch;
        }

        @Override
        public void loadPeriod(Period period, Callback<List<Reading>> callback) {
            callback.onResult(Result.ok(List.of()));
        }

        @Override
        public List<Reading> cachedReadings(Instant from, Instant to) {
            return List.of();
        }
    }

    private static Segment segment(Instant currentTime, List<Reading> readings) {
        return new Segment(currentTime.minus(Duration.ofMinutes(6)), currentTime, readings, List.of(), List.of());
    }
}
