package app.lightscout.storage;

import static app.lightscout.storage.StorageTestSupport.reading;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;

/**
 * Preference files on the device: TST-05-13 (DE part that can run unlocked), -14 DE key set, -15 file location
 * and modes, -17 settings, -18 runtime state, -02 prefs schema.
 *
 * <p>Device: RD-2 (API 33) over USB — batched pre-M1 run (CR-04). The real before-first-unlock observation of
 * TST-05-13 / TST-05-20 is a device-session step in {@code docs/implementation/vr/VR-22.md} (RD-1, API 36).
 */
@RunWith(AndroidJUnit4.class)
public class PrefsFilesTest {

    private Context context;
    private Context deContext;
    private StorageFactory factory;

    @Before
    public void setUp() {
        context = StorageTestSupport.context();
        deContext = context.createDeviceProtectedStorageContext();
        StorageTestSupport.wipe(context);
        factory = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger());
    }

    @After
    public void tearDown() {
        StorageTestSupport.wipe(context);
    }

    private static File prefsFile(Context c, String name) {
        return new File(new File(c.getDataDir(), "shared_prefs"), name + ".xml");
    }

    private static void assertNoWorldAccess(File file) throws Exception {
        assertTrue(file.getPath(), file.exists());
        int mode = Os.stat(file.getPath()).st_mode;
        assertEquals(file.getPath(), 0, mode & (OsConstants.S_IROTH | OsConstants.S_IWOTH | OsConstants.S_IXOTH));
    }

    @Test
    public void tst0513_deFlagsUseOnlyTheDeviceProtectedFile() {
        AppFlagsStore flags = factory.appFlags();
        flags.setMonitoringConfigured(true);
        flags.setHandledBootCount(12L);
        assertTrue(flags.monitoringConfigured());
        assertEquals(12L, flags.handledBootCount());
        assertTrue(prefsFile(deContext, StorageFactory.BOOT_PREFS).exists());
        assertFalse("no CE file opened by DE flags", prefsFile(context, StorageFactory.RUNTIME_PREFS).exists());
        assertFalse(prefsFile(context, StorageFactory.BOOT_PREFS).exists());
        assertFalse(context.getDatabasePath(CacheSchema.DB_NAME).exists());
    }

    @Test
    public void tst0514_bootFileContainsOnlyTheAllowedKeys() {
        AppFlagsStore flags = factory.appFlags();
        flags.setMonitoringConfigured(true);
        flags.setHandledBootCount(3L);
        flags.setDisclaimerAccepted();
        flags.setOnboardingCompleted(true);
        SharedPreferences boot = deContext.getSharedPreferences(StorageFactory.BOOT_PREFS, Context.MODE_PRIVATE);
        assertEquals(Set.of("monitoringConfigured", "handledBootCount", "schema"), boot.getAll().keySet());
    }

    @Test
    public void tst0515_filesAreInAppPrivateCeAndDeDirectoriesWithoutWorldAccess() throws Exception {
        factory.readings().upsert(List.of(reading("r1", 0, 100)));
        factory.settings().put(Settings.defaults());
        factory.connection().save("https://ns.example.test", "fake-token".toCharArray());
        factory.monitorState().put(MonitorState.initial());
        factory.appFlags().setMonitoringConfigured(true);

        File db = context.getDatabasePath(CacheSchema.DB_NAME);
        assertTrue(db.getPath().startsWith(context.getDataDir().getPath()));
        assertNoWorldAccess(db);
        assertNoWorldAccess(new File(db.getPath() + "-wal"));
        assertNoWorldAccess(new File(db.getPath() + "-shm"));
        for (String name : List.of(StorageFactory.SETTINGS_PREFS, StorageFactory.CONNECTION_PREFS,
                StorageFactory.RUNTIME_PREFS)) {
            assertNoWorldAccess(prefsFile(context, name));
        }
        File boot = prefsFile(deContext, StorageFactory.BOOT_PREFS);
        assertTrue(boot.getPath().startsWith(deContext.getDataDir().getPath()));
        assertFalse(boot.getPath().startsWith(context.getDataDir().getPath() + File.separator));
        assertNoWorldAccess(boot);
        new KeystoreTokenCipher().deleteKey();
    }

    @Test
    public void tst0517_settingsDefaultsRoundTripAndListenerOncePerPut() {
        assertEquals(Settings.defaults(), factory.settings().get());
        AtomicInteger calls = new AtomicInteger();
        factory.settings().addListener(calls::incrementAndGet);
        Settings s = new Settings(false, false, 80, 60, false, 200, 30, 20, 25, 90, 45, 2, 14,
                new TargetRange(80, 160));
        factory.settings().put(s);
        assertEquals(s, factory.settings().get());
        assertEquals(1, calls.get());
    }

    @Test
    public void tst0518_runtimeStateSurvivesANewFactory() {
        AlarmState alarm = new AlarmState(app.lightscout.domain.alarm.AlarmType.LOW,
                java.util.Map.of(app.lightscout.domain.alarm.AlarmType.LOW,
                        new app.lightscout.domain.alarm.AlarmTypeState(StorageTestSupport.t(-1), null, 1,
                                StorageTestSupport.t(-2))), 7);
        factory.alarmState().put(alarm);
        StorageFactory restarted = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger());
        assertEquals(alarm, restarted.alarmState().get());
    }

    @Test
    public void tst0518_corruptRuntimeValuesAreLoggedAndReadAsDefaults() {
        StorageTestSupport.TestLogger logger = new StorageTestSupport.TestLogger();
        StorageFactory f = new StorageFactory(context, new StorageTestSupport.TestClock(), logger);
        SharedPreferences runtime = context.getSharedPreferences(StorageFactory.RUNTIME_PREFS, Context.MODE_PRIVATE);
        assertTrue(runtime.edit().putString(PrefsMonitorStateStore.KEY, "{broken")
                .putString(PrefsAlarmStateStore.KEY, "[x").putInt(PrefsReminderStateStore.KEY_SITE, 3)
                .commit());
        assertEquals(MonitorState.initial(), f.monitorState().get());
        assertEquals(AlarmState.empty(), f.alarmState().get());
        assertEquals(java.util.Optional.empty(),
                f.reminderState().get(app.lightscout.domain.consumable.ConsumableKind.SITE));
        assertEquals(List.of("STORAGE_ERROR:monitor_state.get", "STORAGE_ERROR:alarm_state.get",
                "STORAGE_ERROR:reminder_state.get"), logger.lines);
        f.alarmState().put(AlarmState.empty());
        assertEquals(AlarmState.empty(), f.alarmState().get());
    }

    @Test
    public void tst0502_unknownFutureSchemaKeepsValuesAndDefaultsMissingKeys() {
        SharedPreferences settings = context.getSharedPreferences(StorageFactory.SETTINGS_PREFS,
                Context.MODE_PRIVATE);
        assertTrue(settings.edit().putInt(PrefsSchema.KEY, 9).putInt(PrefsSettingsStore.HIGH_MGDL, 230)
                .putString("future_key", "kept").commit());
        Settings s = factory.settings().get();
        assertEquals(230, s.highMgdl());
        assertEquals(70, s.lowMgdl());
        factory.settings().put(s);
        assertEquals(9, settings.getInt(PrefsSchema.KEY, 0));
        assertEquals("kept", settings.getString("future_key", null));
    }
}
