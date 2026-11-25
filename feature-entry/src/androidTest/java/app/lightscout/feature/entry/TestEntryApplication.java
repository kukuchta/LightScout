package app.lightscout.feature.entry;

import android.app.Application;

import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.port.Clock;
import app.lightscout.ui.core.AppInfo;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.StatusRepository;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test stand-in for the CP-15 composition root: IF-28 {@link Dependencies} with fakes for IF-23, IF-21, IF-15 and
 * the real IF-06 {@link EntryRules}.
 */
public class TestEntryApplication extends Application implements DependenciesProvider {

    final ExecutorService io = Executors.newFixedThreadPool(2);
    final EntryRules rules = new EntryRules();
    final Clock clock = new DeviceFakes.DeviceClock();
    volatile DeviceFakes.FakeWriter writer = new DeviceFakes.FakeWriter();
    volatile DeviceFakes.FakeStatusRepository status = new DeviceFakes.FakeStatusRepository();

    /** Fresh fakes for the next test. */
    void reset() {
        writer = new DeviceFakes.FakeWriter();
        status = new DeviceFakes.FakeStatusRepository();
        TestHostActivity.RESULTS.clear();
    }

    @Override
    public Dependencies dependencies() {
        return new Dependencies() {
            @Override
            public <T> T get(Class<T> type) {
                if (type == EntryWriter.class) {
                    return type.cast(writer);
                }
                if (type == EntryRules.class) {
                    return type.cast(rules);
                }
                if (type == StatusRepository.class) {
                    return type.cast(status);
                }
                if (type == Clock.class) {
                    return type.cast(clock);
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
