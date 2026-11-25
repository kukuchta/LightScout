package app.lightscout.feature.stats;

import android.app.Application;

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
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.Segment;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Synthetic, offline-only use-case fakes for S-03 instrumentation tests. */
public final class StatisticsTestApplication extends Application implements DependenciesProvider {

    final FakeDependencies dependencies = new FakeDependencies();

    @Override
    public Dependencies dependencies() {
        return dependencies;
    }

    static final class FakeDependencies implements Dependencies {
        final FakeHistory history = new FakeHistory();
        final FakeClock clock = new FakeClock();
        final FakeSettings settings = new FakeSettings();
        final FakeStatus status = new FakeStatus();
        final androidx.test.espresso.idling.CountingIdlingResource idlingResource =
                new androidx.test.espresso.idling.CountingIdlingResource("statistics-test-io");
        final Executor executor = command -> {
            idlingResource.increment();
            Thread thread = new Thread(() -> {
                try {
                    command.run();
                } finally {
                    idlingResource.decrement();
                }
            }, "statistics-test-io");
            thread.start();
        };

        @Override
        public <T> T get(Class<T> type) {
            Object value;
            if (type == Clock.class) {
                value = clock;
            } else if (type == HistoryLoader.class) {
                value = history;
            } else if (type == SettingsService.class) {
                value = settings;
            } else if (type == StatusRepository.class) {
                value = status;
            } else {
                throw new IllegalStateException("Unexpected dependency " + type.getName());
            }
            return type.cast(value);
        }

        @Override
        public Executor ioExecutor() {
            return executor;
        }

        @Override
        public app.lightscout.ui.core.AppInfo appInfo() {
            return new app.lightscout.ui.core.AppInfo("test");
        }
    }

    static final class FakeHistory implements HistoryLoader {
        volatile boolean failNext = true;
        volatile int readingCount = 80;
        volatile int glucoseMgdl = 75;

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
            if (failNext) {
                failNext = false;
                callback.onResult(Result.fail(FetchOutcome.NS_NOT_RESPONDING, "TIMEOUT"));
                return;
            }
            List<Reading> readings = new ArrayList<>();
            for (int i = 0; i < readingCount; i++) {
                readings.add(new Reading("synthetic-" + i, period.end().minus(Duration.ofMinutes((i + 1) * 5L)),
                        glucoseMgdl, Trend.FLAT, "synthetic"));
            }
            callback.onResult(Result.ok(readings));
        }

        @Override
        public List<Reading> cachedReadings(Instant from, Instant to) {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakeClock implements Clock {
        @Override
        public Instant now() {
            return Instant.parse("2026-10-26T12:00:00Z");
        }

        @Override
        public long elapsedRealtimeMs() {
            return 1L;
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("UTC");
        }
    }

    static final class FakeSettings implements SettingsService {
        @Override
        public Settings get() {
            Settings defaults = Settings.defaults();
            return new Settings(defaults.alarmsEnabled(), defaults.lowEnabled(), defaults.lowMgdl(),
                    defaults.urgentLowMgdl(), defaults.highEnabled(), defaults.highMgdl(),
                    defaults.noDataMinutes(), defaults.lowSnoozeMin(), defaults.urgentLowSnoozeMin(),
                    defaults.highSnoozeMin(), defaults.noDataSnoozeMin(), defaults.siteLifetimeDays(),
                    defaults.sensorLifetimeDays(), new app.lightscout.domain.settings.TargetRange(80, 170));
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
    }

    static final class FakeStatus implements StatusRepository {
        @Override
        public StatusSnapshot current() {
            return new StatusSnapshot(null, null, null, false,
                    new DataLossStatus(DataLossKind.NONE, null, null), FetchOutcome.OK, null,
                    null, null, true, false, true, true, true, true,
                    55, 70, 250, 20, null, null,
                    new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND,
                            null, null, null, null),
                    new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND,
                            null, null, null, null),
                    0, Banner.NONE, Instant.parse("2026-10-26T12:00:00Z"));
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
