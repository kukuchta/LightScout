package app.lightscout.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;

import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.ReminderStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.SupportPromptStore;

/**
 * Builds the {@code :storage} implementations of the store ports for the composition root in {@code :app}
 * (CP-00 R-2, R-6). Each store is created once and then returned again, so listeners and locks are shared.
 *
 * <p><b>Direct boot</b> (CP-00 §6, CP-04 §8.8): constructing the factory touches no storage.
 * {@link #appFlags()} is the only method that may be called while the user is locked; it opens only the
 * device-protected {@code boot} preferences and opens the CE {@code runtime} file lazily, after unlock. Every
 * other method opens credential-encrypted storage (CE preferences, {@code cache.db}, Keystore) and must be called
 * only after unlock.
 *
 * <p><b>Threading</b> (ADR-024): call the factory methods and all store methods off the main thread.
 */
public final class StorageFactory {

    /** ST-02 file name. */
    static final String SETTINGS_PREFS = "settings";
    /** ST-03 file name. */
    static final String CONNECTION_PREFS = "connection";
    /** ST-04 file name. */
    static final String RUNTIME_PREFS = "runtime";
    /** ST-05 file name (device-protected storage). */
    static final String BOOT_PREFS = "boot";

    private final Context context;
    private final Clock clock;
    private final Logger logger;
    private final StoreGuard guard;

    private AppFlagsStore appFlags;
    private CacheDatabase cacheDatabase;
    private ReadingStore readings;
    private EntryStore entries;
    private DeviceStatusStore deviceStatus;
    private SettingsStore settings;
    private ConnectionStore connection;
    private MonitorStateStore monitorState;
    private AlarmStateStore alarmState;
    private ReminderStateStore reminderState;
    private NotifiedAlertStore notifiedAlerts;
    private SupportPromptStore supportPrompt;

    /**
     * Creates the factory without touching any storage.
     *
     * @param context any context; its application context is kept
     * @param clock   IF-15 clock ({@code fetched_at_ms})
     * @param logger  IF-66 logger ({@code STORAGE_ERROR}, {@code KEY_INVALIDATED}, {@code CE_ACCESS_LOCKED})
     */
    public StorageFactory(Context context, Clock clock, Logger logger) {
        this.context = context.getApplicationContext();
        this.clock = clock;
        this.logger = logger;
        this.guard = new StoreGuard(logger);
    }

    /**
     * IF-60. Safe before the first unlock: only the device-protected {@code boot} file is opened here.
     *
     * @return the app flags store
     */
    public synchronized AppFlagsStore appFlags() {
        if (appFlags == null) {
            SharedPreferences boot = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE);
            UserManager userManager = context.getSystemService(UserManager.class);
            appFlags = new PrefsAppFlagsStore(boot, this::runtimePrefs, userManager::isUserUnlocked, logger, guard);
        }
        return appFlags;
    }

    /**
     * IF-08. After unlock only.
     *
     * @return the reading store
     */
    public synchronized ReadingStore readings() {
        if (readings == null) {
            readings = new SqliteReadingStore(cacheDatabase(), clock, guard);
        }
        return readings;
    }

    /**
     * IF-09. After unlock only.
     *
     * @return the entry store
     */
    public synchronized EntryStore entries() {
        if (entries == null) {
            entries = new SqliteEntryStore(cacheDatabase(), clock, guard);
        }
        return entries;
    }

    /**
     * IF-10. After unlock only.
     *
     * @return the device status and profile store
     */
    public synchronized DeviceStatusStore deviceStatus() {
        if (deviceStatus == null) {
            deviceStatus = new SqliteDeviceStatusStore(cacheDatabase(), guard);
        }
        return deviceStatus;
    }

    /**
     * IF-11. After unlock only.
     *
     * @return the settings store
     */
    public synchronized SettingsStore settings() {
        if (settings == null) {
            settings = new PrefsSettingsStore(cePrefs(SETTINGS_PREFS), guard);
        }
        return settings;
    }

    /**
     * IF-12 (also IF-61 {@code TokenSource}). After unlock only.
     *
     * @return the connection store
     */
    public synchronized ConnectionStore connection() {
        if (connection == null) {
            connection = new KeystoreConnectionStore(cePrefs(CONNECTION_PREFS), new KeystoreTokenCipher(), logger,
                    guard);
        }
        return connection;
    }

    /**
     * IF-13. After unlock only.
     *
     * @return the monitor state store
     */
    public synchronized MonitorStateStore monitorState() {
        if (monitorState == null) {
            monitorState = new PrefsMonitorStateStore(runtimePrefs(), new CacheGeneration(runtimePrefs()), guard);
        }
        return monitorState;
    }

    /**
     * IF-14. After unlock only.
     *
     * @return the alarm state store
     */
    public synchronized AlarmStateStore alarmState() {
        if (alarmState == null) {
            alarmState = new PrefsAlarmStateStore(runtimePrefs(), guard);
        }
        return alarmState;
    }

    /**
     * IF-63. After unlock only.
     *
     * @return the reminder state store
     */
    public synchronized ReminderStateStore reminderState() {
        if (reminderState == null) {
            reminderState = new PrefsReminderStateStore(runtimePrefs(), guard);
        }
        return reminderState;
    }

    /**
     * IF-64. After unlock only.
     *
     * @return the notified pump-alert store
     */
    public synchronized NotifiedAlertStore notifiedAlerts() {
        if (notifiedAlerts == null) {
            notifiedAlerts = new PrefsNotifiedAlertStore(runtimePrefs(), guard);
        }
        return notifiedAlerts;
    }

    /**
     * IF-65 (DCR-46). After unlock only.
     *
     * @return the support prompt store
     */
    public synchronized SupportPromptStore supportPrompt() {
        if (supportPrompt == null) {
            supportPrompt = new PrefsSupportPromptStore(runtimePrefs(), guard);
        }
        return supportPrompt;
    }

    private CacheDatabase cacheDatabase() {
        if (cacheDatabase == null) {
            cacheDatabase = new CacheDatabase(context, new CacheGeneration(runtimePrefs()));
        }
        return cacheDatabase;
    }

    private SharedPreferences runtimePrefs() {
        return cePrefs(RUNTIME_PREFS);
    }

    private SharedPreferences cePrefs(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
