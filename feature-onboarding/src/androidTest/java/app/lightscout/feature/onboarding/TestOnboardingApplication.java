package app.lightscout.feature.onboarding;

import android.app.Application;

import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.SystemSettingsIntents;
import app.lightscout.platform.readiness.DefaultReadinessChecker;
import app.lightscout.ui.core.AppInfo;
import app.lightscout.ui.core.Dependencies;
import app.lightscout.ui.core.DependenciesProvider;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.StatusRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test stand-in for the CP-15 composition root: IF-28 {@link Dependencies} with fakes for IF-27, IF-24, IF-21 and the
 * {@code :platform} services S-07 / S-08 use (CMP-27 = the real {@link DefaultReadinessChecker} over an SDK-level
 * probe fake, CMP-26, CMP-28 intents and detector), and a real background {@code ioExecutor}.
 */
public class TestOnboardingApplication extends Application implements DependenciesProvider {

    final ExecutorService io = Executors.newFixedThreadPool(3);
    volatile DeviceFakes.FakeOnboarding onboarding = new DeviceFakes.FakeOnboarding();
    volatile DeviceFakes.ScriptedConnectionService connection = new DeviceFakes.ScriptedConnectionService();
    volatile DeviceFakes.FakeStatusRepository status = new DeviceFakes.FakeStatusRepository();
    volatile DeviceFakes.FakeProbe probe = new DeviceFakes.FakeProbe();
    volatile DeviceFakes.FakeChannels channels = new DeviceFakes.FakeChannels();
    volatile DeviceFakes.FakeIntents intents;
    volatile Manufacturer detected = Manufacturer.XIAOMI;

    /** Fresh fakes for the next test. */
    void reset() {
        onboarding = new DeviceFakes.FakeOnboarding();
        connection = new DeviceFakes.ScriptedConnectionService();
        status = new DeviceFakes.FakeStatusRepository();
        probe = new DeviceFakes.FakeProbe();
        channels = new DeviceFakes.FakeChannels();
        intents = new DeviceFakes.FakeIntents(this, channels);
        detected = Manufacturer.XIAOMI;
        TestHostActivity.FINISHED_ONBOARDING.set(0);
        RecordingSettingsActivity.OPENED.clear();
    }

    @Override
    public Dependencies dependencies() {
        return new Dependencies() {
            @Override
            public <T> T get(Class<T> type) {
                if (type == OnboardingState.class) {
                    return type.cast(onboarding);
                }
                if (type == ConnectionService.class) {
                    return type.cast(connection);
                }
                if (type == StatusRepository.class) {
                    return type.cast(status);
                }
                if (type == ReadinessChecker.class) {
                    final DeviceFakes.FakeProbe p = probe;
                    return type.cast(new DefaultReadinessChecker(p));
                }
                if (type == NotificationChannels.class) {
                    return type.cast(channels);
                }
                if (type == SystemSettingsIntents.class) {
                    if (intents == null) {
                        intents = new DeviceFakes.FakeIntents(TestOnboardingApplication.this, channels);
                    }
                    return type.cast(intents);
                }
                if (type == ManufacturerDetector.class) {
                    final Manufacturer m = detected;
                    return type.cast((ManufacturerDetector) () -> m);
                }
                throw new IllegalStateException("not registered in the test: " + type.getName());
            }

            @Override
            public java.util.concurrent.Executor ioExecutor() {
                return io;
            }

            @Override
            public AppInfo appInfo() {
                return new AppInfo("0.1.0");
            }
        };
    }
}
