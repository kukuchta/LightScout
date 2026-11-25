package app.lightscout.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.SettingsRules;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.ui.core.AppInfo;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StoredConnection;
import app.lightscout.usecases.common.SettingsEditor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** S-04 use-case routing, immediate state, and save de-duplication tests with fake ports. */
public class SettingsViewModelTest {

    private final FakeSettings settings = new FakeSettings();
    private final FakeActions actions = new FakeActions();
    private final FakeConnection connection = new FakeConnection();
    private final FakeReadiness readiness = new FakeReadiness();
    private SettingsViewModel viewModel;

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
    public void loadsSettingsHostAndRefreshesReadinessOnResume() {
        readiness.missing = 3;
        viewModel = create(Runnable::run);

        SettingsUiState state = viewModel.state().getValue();
        assertTrue(state.loaded());
        assertEquals(Settings.defaults(), state.settings());
        assertEquals("example.org", state.host());
        assertEquals(0, state.missingRequiredCount());

        viewModel.refreshReadiness();
        assertEquals(3, viewModel.state().getValue().missingRequiredCount());
    }

    @Test
    public void alarmSwitchesRouteThroughIf25AndUpdateDisplayedState() {
        viewModel = create(Runnable::run);

        viewModel.setAlarmsEnabled(false);
        viewModel.setAlarmTypeEnabled(AlarmType.LOW, false);
        viewModel.setAlarmTypeEnabled(AlarmType.HIGH, false);

        assertEquals(List.of(false), actions.masterChanges);
        assertEquals(List.of(new TypeChange(AlarmType.LOW, false),
                new TypeChange(AlarmType.HIGH, false)), actions.typeChanges);
        Settings current = viewModel.state().getValue().settings();
        assertFalse(current.alarmsEnabled());
        assertFalse(current.lowEnabled());
        assertFalse(current.highEnabled());
    }

    @Test
    public void saveUsesIf26OnceAndPublishesAcceptedValue() {
        List<Runnable> tasks = new ArrayList<>();
        viewModel = create(tasks::add);
        assertEquals(1, tasks.size());
        tasks.remove(0).run();

        List<ValidationResult> callbacks = new ArrayList<>();
        SettingChange change = new SettingChange(app.lightscout.domain.settings.SettingKey.LOW_SNOOZE,
                20, null);
        assertTrue(viewModel.save(change, callbacks::add));
        assertFalse("Repeated taps while the save is queued are ignored", viewModel.save(change, callbacks::add));
        assertEquals(0, settings.updateCalls);
        assertEquals(1, tasks.size());

        tasks.remove(0).run();
        assertEquals(1, settings.updateCalls);
        assertEquals(1, callbacks.size());
        assertTrue(callbacks.get(0).valid());
        assertEquals(20, viewModel.state().getValue().settings().lowSnoozeMin());
    }

    @Test
    public void statisticsCardToggleUpdatesScreenAndStoreAndSurvivesOtherSwitches() {
        viewModel = create(Runnable::run);

        viewModel.setStatisticsCardOnHome(false);
        assertEquals(1, settings.updateCalls);
        assertFalse(settings.current.statisticsCardOnHome());
        assertFalse(viewModel.state().getValue().settings().statisticsCardOnHome());

        viewModel.setAlarmsEnabled(false);
        viewModel.setAlarmTypeEnabled(AlarmType.LOW, false);
        assertFalse("alarm switches keep the card value", viewModel.state().getValue().settings().statisticsCardOnHome());

        viewModel.setStatisticsCardOnHome(true);
        assertTrue(viewModel.state().getValue().settings().statisticsCardOnHome());
        assertTrue(settings.current.statisticsCardOnHome());
    }

    @Test
    public void statisticsCardToggleReturnsToTheStoredValueWhenTheStoreKeepsNothing() {
        viewModel = create(Runnable::run);
        settings.keepNothing = true;

        viewModel.setStatisticsCardOnHome(false);

        assertEquals(1, settings.updateCalls);
        assertTrue(settings.current.statisticsCardOnHome());
        assertTrue("screen shows the stored value", viewModel.state().getValue().settings().statisticsCardOnHome());
    }

    private SettingsViewModel create(Executor executor) {
        return new SettingsViewModel(settings, actions, connection, readiness, executor,
                new AppInfo("1.2.3"));
    }

    private static final class FakeSettings implements SettingsService {
        Settings current = Settings.defaults();
        Consumer<Settings> listener;
        int updateCalls;
        boolean keepNothing;

        @Override
        public Settings get() {
            return current;
        }

        @Override
        public ValidationResult update(SettingChange change) {
            updateCalls++;
            if (keepNothing) {
                return SettingsRules.validate(current, change);
            }
            ValidationResult result = SettingsRules.validate(current, change);
            if (result.valid()) {
                current = SettingsEditor.withChange(current, change);
                if (listener != null) {
                    listener.accept(current);
                }
            }
            return result;
        }

        @Override
        public void addListener(Consumer<Settings> listener) {
            this.listener = listener;
        }

        @Override
        public void removeListener(Consumer<Settings> listener) {
            if (this.listener == listener) {
                this.listener = null;
            }
        }
    }

    private static final class FakeActions implements AlarmActions {
        final List<Boolean> masterChanges = new ArrayList<>();
        final List<TypeChange> typeChanges = new ArrayList<>();

        @Override
        public void snooze(AlarmType type) {
            throw new AssertionError("S-04 does not snooze alarms");
        }

        @Override
        public void setAlarmsEnabled(boolean on) {
            masterChanges.add(on);
        }

        @Override
        public void setAlarmTypeEnabled(AlarmType type, boolean on) {
            typeChanges.add(new TypeChange(type, on));
        }
    }

    private record TypeChange(AlarmType type, boolean enabled) {
    }

    private static final class FakeConnection implements ConnectionService {
        @Override
        public void loadStored(Callback<StoredConnection> callback) {
            callback.onResult(Result.ok(new StoredConnection("https://example.org", "example.org", true)));
        }

        @Override
        public void verify(String url, char[] tokenOrNull, Callback<List<app.lightscout.usecases.CheckResult>> cb) {
            throw new AssertionError("S-04 never verifies a connection");
        }

        @Override
        public void revealStoredToken(Callback<char[]> cb) {
            throw new AssertionError("S-04 never reads the token");
        }

        @Override
        public void save(String url, char[] tokenOrNull, Callback<app.lightscout.usecases.SaveResult> cb) {
            throw new AssertionError("S-04 never saves a connection");
        }
    }

    private static final class FakeReadiness implements ReadinessChecker {
        int missing;

        @Override
        public List<app.lightscout.platform.ReadinessItem> check() {
            throw new AssertionError("S-04 only reads the readiness count");
        }

        @Override
        public int missingRequiredCount() {
            return missing;
        }
    }
}
