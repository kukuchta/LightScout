package app.lightscout.feature.home;

import android.app.Application;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.model.Reading;
import app.lightscout.ui.core.AppInfo;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.Segment;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.domain.statistics.Period;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test stand-in for the CP-15 composition root: IF-28 {@link Dependencies} with fakes for IF-21, IF-25 and IF-15 —
 * exactly the types S-01 (CP-12a) looks up.
 */
public class TestHomeApplication extends Application implements DependenciesProvider {

    final ExecutorService io = Executors.newFixedThreadPool(3);
    volatile DeviceFakes.FakeStatusRepository status = new DeviceFakes.FakeStatusRepository();
    volatile DeviceFakes.FakeAlarmActions alarms = new DeviceFakes.FakeAlarmActions();
    final HistoryLoader history = new HistoryLoader() {
        @Override
        public void loadSegment(java.time.Instant from, java.time.Instant to, Callback<Segment> callback) {
            callback.onResult(Result.fail(FetchOutcome.NO_INTERNET, "NO_NETWORK"));
        }

        @Override
        public void loadCachedSegment(java.time.Instant from, java.time.Instant to, Callback<Segment> callback) {
            callback.onResult(Result.ok(new Segment(from, to, java.util.List.of(), java.util.List.of(),
                    java.util.List.of())));
        }

        @Override
        public void loadPeriod(Period period, Callback<java.util.List<Reading>> callback) {
            callback.onResult(Result.ok(java.util.List.of()));
        }

        @Override
        public java.util.List<Reading> cachedReadings(java.time.Instant from, java.time.Instant to) {
            return java.util.List.of();
        }
    };
    final SettingsService settings = new SettingsService() {
        @Override
        public Settings get() {
            return Settings.defaults();
        }

        @Override
        public ValidationResult update(SettingChange change) {
            return ValidationResult.ok();
        }

        @Override
        public void addListener(java.util.function.Consumer<Settings> listener) {
        }

        @Override
        public void removeListener(java.util.function.Consumer<Settings> listener) {
        }
    };
    final Clock clock = new DeviceFakes.SystemClockFake();

    /** Fresh fakes for the next test. */
    void reset() {
        status = new DeviceFakes.FakeStatusRepository();
        alarms = new DeviceFakes.FakeAlarmActions();
        TestHostActivity.NAVIGATIONS.clear();
    }

    @Override
    public Dependencies dependencies() {
        return new Dependencies() {
            @Override
            public <T> T get(Class<T> type) {
                if (type == StatusRepository.class) {
                    return type.cast(status);
                }
                if (type == AlarmActions.class) {
                    return type.cast(alarms);
                }
                if (type == Clock.class) {
                    return type.cast(clock);
                }
                if (type == HistoryLoader.class) {
                    return type.cast(history);
                }
                if (type == SettingsService.class) {
                    return type.cast(settings);
                }
                if (type == app.lightscout.domain.port.SupportPromptStore.class) {
                    return type.cast(new app.lightscout.domain.port.SupportPromptStore() {
                        @Override
                        public java.time.Instant firstUse(java.time.Instant now) {
                            return now;   // first use = now: the prompt never appears in a screen test
                        }

                        @Override
                        public boolean shown() {
                            return false;
                        }

                        @Override
                        public void markShown() {
                            // not needed
                        }
                    });
                }
                throw new IllegalStateException("not registered in the test: " + type.getName());
            }

            @Override
            public Executor ioExecutor() {
                return io;
            }

            @Override
            public AppInfo appInfo() {
                return new AppInfo("0.1.0");
            }
        };
    }
}
