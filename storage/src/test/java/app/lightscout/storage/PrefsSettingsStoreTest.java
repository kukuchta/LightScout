package app.lightscout.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.settings.TargetRange;
import app.lightscout.domain.settings.ThemeMode;

/** IF-11 / TST-05-17 settings defaults, round trip, listener; TST-05-02 prefs schema part (ADR-033). */
public class PrefsSettingsStoreTest {

    private final FakeSharedPreferences prefs = new FakeSharedPreferences();
    private final RecordingLogger logger = new RecordingLogger();
    private final PrefsSettingsStore store = new PrefsSettingsStore(prefs, new StoreGuard(logger));

    private static Settings custom() {
        return new Settings(false, false, 80, 60, false, 200, 30, 20, 25, 90, 45, 2, 14, new TargetRange(80, 160));
    }

    @Test
    public void freshInstallReturnsTheDefaultsOfSection83() {
        Settings s = store.get();
        assertEquals(Settings.defaults(), s);
        assertEquals(true, s.alarmsEnabled());
        assertEquals(true, s.lowEnabled());
        assertEquals(70, s.lowMgdl());
        assertEquals(55, s.urgentLowMgdl());
        assertEquals(true, s.highEnabled());
        assertEquals(250, s.highMgdl());
        assertEquals(20, s.noDataMinutes());
        assertEquals(15, s.lowSnoozeMin());
        assertEquals(15, s.urgentLowSnoozeMin());
        assertEquals(60, s.highSnoozeMin());
        assertEquals(30, s.noDataSnoozeMin());
        assertEquals(3, s.siteLifetimeDays());
        assertEquals(10, s.sensorLifetimeDays());
        assertEquals(new TargetRange(70, 180), s.targetRange());
    }

    @Test
    public void themeModeRoundTripsAndUnknownCodesReadAsSystem() {
        assertEquals(ThemeMode.SYSTEM, store.get().themeMode());
        Settings d = Settings.defaults();
        for (ThemeMode mode : ThemeMode.values()) {
            store.put(new Settings(d.alarmsEnabled(), d.lowEnabled(), d.lowMgdl(), d.urgentLowMgdl(), d.highEnabled(),
                    d.highMgdl(), d.noDataMinutes(), d.lowSnoozeMin(), d.urgentLowSnoozeMin(), d.highSnoozeMin(),
                    d.noDataSnoozeMin(), d.siteLifetimeDays(), d.sensorLifetimeDays(), d.targetRange(),
                    d.statisticsCardOnHome(), mode));
            assertEquals(mode, store.get().themeMode());
        }
        prefs.edit().putInt(PrefsSettingsStore.THEME_MODE, 99).commit();
        assertEquals(ThemeMode.SYSTEM, store.get().themeMode());
    }

    @Test
    public void putThenGetRoundTripsEveryField() {
        store.put(custom());
        assertEquals(custom(), store.get());
        assertEquals(1, prefs.commits);
        assertEquals("commit(), never apply()", 0, prefs.applies);
    }

    @Test
    public void listenerFiresOncePerPut() {
        AtomicInteger calls = new AtomicInteger();
        store.addListener(calls::incrementAndGet);
        store.put(custom());
        assertEquals(1, calls.get());
        store.put(Settings.defaults());
        assertEquals(2, calls.get());
    }

    @Test
    public void failedCommitThrowsStorageExceptionLogsOnceAndSkipsListeners() {
        AtomicInteger calls = new AtomicInteger();
        store.addListener(calls::incrementAndGet);
        prefs.failCommits = true;
        StorageException e = assertThrows(StorageException.class, () -> store.put(custom()));
        assertEquals("settings.put", e.op());
        assertEquals(List.of("STORAGE_ERROR:settings.put"), logger.lines);
        assertEquals(0, calls.get());
    }

    @Test
    public void readFailureThrowsStorageException() {
        prefs.failReads = true;
        StorageException e = assertThrows(StorageException.class, store::get);
        assertEquals("settings.get", e.op());
        assertEquals(List.of("STORAGE_ERROR:settings.get"), logger.lines);
    }

    @Test
    public void missingKeysReadAsTheirDefaults() {
        prefs.values.put(PrefsSettingsStore.LOW_MGDL, 85);
        prefs.values.put(PrefsSettingsStore.TARGET_HIGH_MGDL, 170);
        Settings s = store.get();
        assertEquals(85, s.lowMgdl());
        assertEquals(new TargetRange(70, 170), s.targetRange());
        assertEquals(250, s.highMgdl());
    }

    @Test
    public void putStampsSchemaVersionOne() {
        store.put(custom());
        assertEquals(1, prefs.values.get(PrefsSchema.KEY));
    }

    @Test
    public void unknownFutureSchemaKeepsValuesDefaultsMissingKeysAndIsNeverLowered() {
        prefs.values.put(PrefsSchema.KEY, 7);
        prefs.values.put(PrefsSettingsStore.HIGH_MGDL, 220);
        prefs.values.put("future_only_key", "kept");
        Settings s = store.get();
        assertEquals(220, s.highMgdl());
        assertEquals(55, s.urgentLowMgdl());

        store.put(s);
        assertEquals(7, prefs.values.get(PrefsSchema.KEY));
        assertEquals("kept", prefs.values.get("future_only_key"));
    }
}
