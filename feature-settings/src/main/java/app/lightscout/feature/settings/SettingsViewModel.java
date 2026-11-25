package app.lightscout.feature.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.entry.ValidationResult;
import app.lightscout.domain.settings.SettingChange;
import app.lightscout.domain.settings.SettingKey;
import app.lightscout.domain.settings.Settings;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.ui.core.AppInfo;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StoredConnection;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** S-04 coordinator. Blocking preference work is always dispatched to the injected I/O executor. */
public final class SettingsViewModel extends ViewModel {

    private final SettingsService settingsService;
    private final AlarmActions alarmActions;
    private final ConnectionService connectionService;
    private final ReadinessChecker readinessChecker;
    private final Executor ioExecutor;
    private final AppInfo appInfo;
    private final MutableLiveData<SettingsUiState> state = new MutableLiveData<>();
    private final AtomicLong settingsRevision = new AtomicLong();
    private final AtomicBoolean saveInFlight = new AtomicBoolean();
    private final Object stateLock = new Object();
    private final Consumer<Settings> settingsListener = this::onSettingsChanged;
    private volatile SettingsUiState current = new SettingsUiState(Settings.defaults(), "", 0, false);
    private volatile boolean cleared;

    public SettingsViewModel(SettingsService settingsService, AlarmActions alarmActions,
                             ConnectionService connectionService, ReadinessChecker readinessChecker,
                             Executor ioExecutor, AppInfo appInfo) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.alarmActions = Objects.requireNonNull(alarmActions, "alarmActions");
        this.connectionService = Objects.requireNonNull(connectionService, "connectionService");
        this.readinessChecker = Objects.requireNonNull(readinessChecker, "readinessChecker");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.appInfo = Objects.requireNonNull(appInfo, "appInfo");
        settingsService.addListener(settingsListener);
        loadSettings();
        loadHost();
    }

    public LiveData<SettingsUiState> state() {
        return state;
    }

    public AppInfo appInfo() {
        return appInfo;
    }

    SettingsUiState snapshot() {
        return current;
    }

    /** Rechecks local platform state every time S-04 resumes, including after returning from S-07. */
    public void refreshReadiness() {
        try {
            int count = readinessChecker.missingRequiredCount();
            update(old -> new SettingsUiState(old.settings(), old.host(), count, old.loaded()));
        } catch (RuntimeException ignored) {
            // CMP-27 queries local system state; retain the last successful projection if it is unavailable.
        }
    }

    public void setAlarmsEnabled(boolean enabled) {
        alarmActions.setAlarmsEnabled(enabled);
        update(old -> new SettingsUiState(copy(old.settings(), enabled, old.settings().lowEnabled(),
                old.settings().highEnabled()), old.host(), old.missingRequiredCount(), old.loaded()));
    }

    /**
     * Shows or hides the statistics card on Home; the screen follows at once and the store is updated async. When
     * the store did not keep the change, the screen returns to the stored value.
     */
    public void setStatisticsCardOnHome(boolean shown) {
        update(old -> {
            Settings b = old.settings();
            Settings changed = new Settings(b.alarmsEnabled(), b.lowEnabled(), b.lowMgdl(), b.urgentLowMgdl(),
                    b.highEnabled(), b.highMgdl(), b.noDataMinutes(), b.lowSnoozeMin(), b.urgentLowSnoozeMin(),
                    b.highSnoozeMin(), b.noDataSnoozeMin(), b.siteLifetimeDays(), b.sensorLifetimeDays(),
                    b.targetRange(), shown, b.themeMode());
            return new SettingsUiState(changed, old.host(), old.missingRequiredCount(), old.loaded());
        });
        ioExecutor.execute(() -> {
            try {
                settingsService.update(new SettingChange(SettingKey.STATISTICS_CARD, shown ? 1 : 0, null));
            } catch (RuntimeException ignored) {
                // Handled below: the stored value decides what the screen shows.
            }
            try {
                Settings stored = settingsService.get();
                if (stored.statisticsCardOnHome() != shown) {
                    setSettings(stored, true);
                }
            } catch (RuntimeException ignored) {
                // The stored value cannot be read either; keep the screen as it is.
            }
        });
    }

    public void setAlarmTypeEnabled(AlarmType type, boolean enabled) {
        alarmActions.setAlarmTypeEnabled(type, enabled);
        update(old -> {
            Settings before = old.settings();
            Settings changed = type == AlarmType.LOW
                    ? copy(before, before.alarmsEnabled(), enabled, before.highEnabled())
                    : copy(before, before.alarmsEnabled(), before.lowEnabled(), enabled);
            return new SettingsUiState(changed, old.host(), old.missingRequiredCount(), old.loaded());
        });
    }

    /**
     * Persists one validated change. The callback runs on the executor thread; UI callers must marshal it to main.
     *
     * @return false when another save is already in flight
     */
    public boolean save(SettingChange change, Consumer<ValidationResult> callback) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(callback, "callback");
        if (!saveInFlight.compareAndSet(false, true)) {
            return false;
        }
        ioExecutor.execute(() -> {
            ValidationResult result;
            try {
                result = settingsService.update(change);
            } catch (RuntimeException e) {
                result = null;
            } finally {
                saveInFlight.set(false);
            }
            if (!cleared) {
                callback.accept(result);
            }
        });
        return true;
    }

    private void loadSettings() {
        long revision = settingsRevision.get();
        ioExecutor.execute(() -> {
            try {
                Settings loaded = settingsService.get();
                if (revision == settingsRevision.get()) {
                    setSettings(loaded, true);
                }
            } catch (RuntimeException ignored) {
                // The feature has no storage-error copy; keep controls disabled rather than showing stale defaults.
            }
        });
    }

    private void loadHost() {
        try {
            connectionService.loadStored(result -> {
                if (cleared) {
                    return;
                }
                StoredConnection stored = result.ok() ? result.value() : null;
                update(old -> new SettingsUiState(old.settings(),
                        stored == null ? "" : stored.host(), old.missingRequiredCount(), old.loaded()));
            });
        } catch (RuntimeException ignored) {
            // Host is informational; a failed load never affects settings or monitoring.
        }
    }

    private void onSettingsChanged(Settings settings) {
        settingsRevision.incrementAndGet();
        setSettings(settings, true);
    }

    private void setSettings(Settings settings, boolean loaded) {
        update(old -> new SettingsUiState(settings, old.host(), old.missingRequiredCount(), loaded));
    }

    private void update(java.util.function.UnaryOperator<SettingsUiState> reducer) {
        synchronized (stateLock) {
            if (cleared) {
                return;
            }
            current = reducer.apply(current);
            state.postValue(current);
        }
    }

    private static Settings copy(Settings before, boolean alarmsEnabled, boolean lowEnabled, boolean highEnabled) {
        return new Settings(alarmsEnabled, lowEnabled, before.lowMgdl(), before.urgentLowMgdl(), highEnabled,
                before.highMgdl(), before.noDataMinutes(), before.lowSnoozeMin(), before.urgentLowSnoozeMin(),
                before.highSnoozeMin(), before.noDataSnoozeMin(), before.siteLifetimeDays(),
                before.sensorLifetimeDays(), before.targetRange(), before.statisticsCardOnHome(),
                before.themeMode());
    }

    @Override
    protected void onCleared() {
        cleared = true;
        settingsService.removeListener(settingsListener);
    }
}
