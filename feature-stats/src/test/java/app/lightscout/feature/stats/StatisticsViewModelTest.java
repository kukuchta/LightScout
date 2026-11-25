package app.lightscout.feature.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.statistics.PeriodKind;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.feature.stats.StatisticsUiState.Screen;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.Segment;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** S-03 state, period mapping, stale-request, offline, and retry rules with faked use cases. */
public class StatisticsViewModelTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private final FakeClock clock = new FakeClock();
    private final FakeHistory history = new FakeHistory();
    private final FakeStatus status = new FakeStatus(true);
    private StatisticsViewModel viewModel;

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
    }

    @After
    public void tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null);
    }

    @Test
    public void startsOnYesterdayAndMapsEveryAvailablePeriod() {
        viewModel = create(Runnable::run);
        assertEquals(PeriodKind.YESTERDAY, viewModel.state().getValue().selectedPeriod());
        assertEquals(PeriodKind.YESTERDAY, history.periods.get(0).kind());

        viewModel.select(PeriodKind.LAST_7_DAYS);
        viewModel.select(PeriodKind.LAST_14_DAYS);
        viewModel.select(PeriodKind.LAST_30_DAYS);
        assertEquals(List.of(PeriodKind.YESTERDAY, PeriodKind.LAST_7_DAYS, PeriodKind.LAST_14_DAYS,
                PeriodKind.LAST_30_DAYS), history.periods.stream().map(Period::kind).toList());
        assertEquals(Duration.ofDays(7), Duration.between(history.periods.get(1).start(),
                history.periods.get(1).end()));
        assertEquals(Duration.ofDays(14), Duration.between(history.periods.get(2).start(),
                history.periods.get(2).end()));
        assertEquals(Duration.ofDays(30), Duration.between(history.periods.get(3).start(),
                history.periods.get(3).end()));
    }

    @Test
    public void yesterdayUsesLocalCalendarBoundsAcrossDst() {
        clock.now = Instant.parse("2024-11-04T17:00:00Z");
        clock.zone = ZoneId.of("America/New_York");
        viewModel = create(Runnable::run);
        Period yesterday = history.periods.get(0);
        assertEquals(PeriodKind.YESTERDAY, yesterday.kind());
        assertEquals(Duration.ofHours(25), Duration.between(yesterday.start(), yesterday.end()));
        assertEquals(300L, yesterday.expectedReadings());
    }

    @Test
    public void stalePeriodResultIsIgnoredAndRetryDoesNotDuplicateInflightCall() {
        List<Runnable> tasks = new ArrayList<>();
        viewModel = create(tasks::add);
        assertEquals(1, tasks.size());
        tasks.remove(0).run();
        assertEquals(1, history.callbacks.size());

        viewModel.retry();
        assertEquals("Retry while loading is ignored", 0, tasks.size());
        viewModel.select(PeriodKind.LAST_7_DAYS);
        assertEquals(1, tasks.size());
        Callback<List<Reading>> stale = history.callbacks.get(0);
        stale.onResult(Result.ok(readings(history.periods.get(0), 1, 120)));
        assertEquals(Screen.LOADING, viewModel.state().getValue().screen());

        tasks.remove(0).run();
        assertEquals(2, history.callbacks.size());
        history.callbacks.get(1).onResult(Result.ok(readings(history.periods.get(1), 1, 120)));
        assertEquals(PeriodKind.LAST_7_DAYS, viewModel.state().getValue().selectedPeriod());
        assertEquals(Screen.PARTIAL, viewModel.state().getValue().screen());
    }

    @Test
    public void emptyPartialAndSeventyPercentBoundaryMapToTheSpecifiedStates() {
        viewModel = create(Runnable::run);
        history.respond(0, Result.ok(List.of()));
        assertEquals(Screen.EMPTY, viewModel.state().getValue().screen());
        assertTrue(viewModel.state().getValue().statistics().empty());

        viewModel.select(PeriodKind.LAST_30_DAYS);
        history.respond(1, Result.ok(readings(history.periods.get(1), 5000, 120)));
        assertEquals(Screen.PARTIAL, viewModel.state().getValue().screen());

        viewModel.retry();
        history.respond(2, Result.ok(readings(history.periods.get(2), 6048, 120)));
        assertFalse(viewModel.state().getValue().statistics().partial());
        assertEquals(Screen.CONTENT, viewModel.state().getValue().screen());
    }

    @Test
    public void offlineCacheCopyUsesSnapshotAndFailureOutcomesSelectTheCorrectState() {
        viewModel = create(Runnable::run);
        status.online = false;
        history.respond(0, Result.ok(readings(history.periods.get(0), 10, 120)));
        assertTrue(viewModel.state().getValue().offlineFromCache());
        assertEquals(Screen.PARTIAL, viewModel.state().getValue().screen());

        viewModel.select(PeriodKind.LAST_7_DAYS);
        history.respond(1, Result.fail(FetchOutcome.NO_INTERNET, "NO_NETWORK"));
        assertEquals(Screen.OFFLINE, viewModel.state().getValue().screen());

        viewModel.select(PeriodKind.LAST_14_DAYS);
        history.respond(2, Result.fail(FetchOutcome.NS_NOT_RESPONDING, "TIMEOUT"));
        assertEquals(Screen.ERROR, viewModel.state().getValue().screen());

        viewModel.select(PeriodKind.LAST_30_DAYS);
        history.respond(3, Result.fail(FetchOutcome.PERMISSION_MISSING, "MISSING_READ"));
        assertEquals(Screen.ERROR, viewModel.state().getValue().screen());
    }

    @Test
    public void settingsAndHistoryCallsRunOnInjectedIoExecutor() {
        List<Runnable> tasks = new ArrayList<>();
        AtomicInteger settingsReads = new AtomicInteger();
        viewModel = new StatisticsViewModel(clock, history, settings(settingsReads), status, tasks::add);
        assertEquals(0, settingsReads.get());
        assertEquals(1, tasks.size());
        tasks.remove(0).run();
        assertEquals(1, settingsReads.get());
        assertEquals(1, history.periods.size());
    }

    private StatisticsViewModel create(Executor executor) {
        return new StatisticsViewModel(clock, history, settings(new AtomicInteger()), status, executor);
    }

    private static SettingsService settings(AtomicInteger reads) {
        return new SettingsService() {
            @Override
            public Settings get() {
                reads.incrementAndGet();
                return Settings.defaults();
            }

            @Override
            public app.lightscout.domain.entry.ValidationResult update(SettingChange change) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void addListener(Consumer<Settings> listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void removeListener(Consumer<Settings> listener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static List<Reading> readings(Period period, int count, int sgv) {
        List<Reading> readings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            readings.add(new Reading("id-" + i, period.end().minus(Duration.ofMinutes((i + 1) * 5L)),
                    sgv, Trend.FLAT, "fake"));
        }
        return readings;
    }

    private static final class FakeClock implements Clock {
        Instant now = NOW;
        ZoneId zone = ZoneId.of("Europe/Warsaw");

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public long elapsedRealtimeMs() {
            return now.toEpochMilli();
        }

        @Override
        public ZoneId zone() {
            return zone;
        }
    }

    private static final class FakeHistory implements HistoryLoader {
        final List<Period> periods = new ArrayList<>();
        final List<Callback<List<Reading>>> callbacks = new ArrayList<>();

        @Override
        public void loadSegment(Instant from, Instant to, Callback<Segment> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void loadCachedSegment(Instant from, Instant to, Callback<Segment> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void loadPeriod(Period period, Callback<List<Reading>> callback) {
            periods.add(period);
            callbacks.add(callback);
        }

        @Override
        public List<Reading> cachedReadings(Instant from, Instant to) {
            throw new UnsupportedOperationException();
        }

        void respond(int index, Result<List<Reading>> result) {
            callbacks.get(index).onResult(result);
        }
    }

    private static final class FakeStatus implements StatusRepository {
        boolean online;

        FakeStatus(boolean online) {
            this.online = online;
        }

        @Override
        public StatusSnapshot current() {
            return new StatusSnapshot(null, null, null, false,
                    new DataLossStatus(DataLossKind.NONE, null, null), FetchOutcome.OK, null,
                    null, null, online, false, true, true, true, true,
                    55, 70, 250, 20, null, null,
                    new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND,
                            null, null, null, null),
                    new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND,
                            null, null, null, null),
                    0, Banner.NONE, NOW);
        }

        @Override
        public void addListener(StatusListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeListener(StatusListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void refresh() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retry() {
            throw new UnsupportedOperationException();
        }
    }
}
