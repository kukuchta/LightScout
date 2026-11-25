package app.lightscout.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TST-05-01 schema and TST-05-02 migration (cache part) on a real SQLite.
 *
 * <p>Device: RD-2 (API 33) over USB — batched pre-M1 run (CR-04). API 29 / 36 bands: no band-specific logic.
 */
@RunWith(AndroidJUnit4.class)
public class CacheDatabaseTest {

    private Context context;

    @Before
    public void setUp() {
        context = StorageTestSupport.context();
        StorageTestSupport.wipe(context);
    }

    @After
    public void tearDown() {
        StorageTestSupport.wipe(context);
    }

    private static Set<String> names(SQLiteDatabase db, String type) {
        Set<String> names = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE 'sqlite_%'"
                + " AND name NOT LIKE 'android_%'", new String[] {type})) {
            while (c.moveToNext()) {
                names.add(c.getString(0));
            }
        }
        return names;
    }

    private static String pragma(SQLiteDatabase db, String pragma) {
        try (Cursor c = db.rawQuery("PRAGMA " + pragma, null)) {
            c.moveToFirst();
            return c.getString(0);
        }
    }

    @Test
    public void tst0501_freshDatabaseHasTablesIndexesWalAndCurrentUserVersion() {
        CacheDatabase helper = StorageTestSupport.cacheDatabase(context);
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            assertEquals(Set.of("readings", "entries", "device_status", "profile"), names(db, "table"));
            assertEquals(Set.of("readings_time", "entries_time", "entries_type_time", "device_status_time"),
                    names(db, "index"));
            assertFalse(names(db, "table").contains("sync_meta"));
            assertEquals("wal", pragma(db, "journal_mode").toLowerCase(Locale.ROOT));
            assertEquals(CacheSchema.VERSION, db.getVersion());
        } finally {
            helper.close();
        }
    }

    private void createForeignDatabase(int version) {
        File file = context.getDatabasePath(CacheSchema.DB_NAME);
        file.getParentFile().mkdirs();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE readings (id TEXT PRIMARY KEY, legacy_value INTEGER)");
            db.execSQL("INSERT INTO readings (id, legacy_value) VALUES ('old-1', 5)");
            db.execSQL("CREATE TABLE sync_meta (collection TEXT PRIMARY KEY)");
            db.setVersion(version);
        }
    }

    private void assertFreshEmptyCache() {
        CacheDatabase helper = StorageTestSupport.cacheDatabase(context);
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            assertEquals(CacheSchema.VERSION, db.getVersion());
            assertEquals(Set.of("readings", "entries", "device_status", "profile"), names(db, "table"));
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM readings", null)) {
                c.moveToFirst();
                assertEquals(0, c.getInt(0));
            }
            try (Cursor c = db.rawQuery("PRAGMA table_info(readings)", null)) {
                Set<String> columns = new HashSet<>();
                while (c.moveToNext()) {
                    columns.add(c.getString(1));
                }
                assertEquals(Set.of("id", "time_ms", "sgv", "direction", "device", "fetched_at_ms"), columns);
            }
        } finally {
            helper.close();
        }
    }

    @Test
    public void tst0502_higherUserVersionDropsAndRecreatesTheCache() {
        createForeignDatabase(5);
        assertFreshEmptyCache();
    }

    @Test
    public void tst0502_lowerUnversionedFileWithStaleTablesIsRecreated() {
        createForeignDatabase(0);
        assertFreshEmptyCache();
    }

    @Test
    public void tst0502_recreationResetsTheSyncCursorsSoTheCacheIsRefetched() {
        StorageFactory before = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger());
        java.util.Map<app.lightscout.domain.model.NsCollection, app.lightscout.domain.monitor.CollectionCursor>
                cursors = java.util.Map.of(app.lightscout.domain.model.NsCollection.ENTRIES,
                new app.lightscout.domain.monitor.CollectionCursor(123L, StorageTestSupport.t(-60),
                        StorageTestSupport.t(-30), StorageTestSupport.t(-44_640), true, 456L));
        app.lightscout.domain.monitor.MonitorState state = new app.lightscout.domain.monitor.MonitorState(
                StorageTestSupport.t(-600), StorageTestSupport.t(-1), StorageTestSupport.t(0),
                app.lightscout.domain.port.FetchOutcome.OK, null, StorageTestSupport.t(-4), 60,
                java.util.List.of(60), null, cursors);
        before.monitorState().get();
        before.monitorState().put(state);
        assertEquals(state, before.monitorState().get());

        createForeignDatabase(5);
        StorageFactory after = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger());
        after.readings().range(StorageTestSupport.t(0), StorageTestSupport.t(1));   // opens → re-creates
        app.lightscout.domain.monitor.MonitorState read = after.monitorState().get();
        assertTrue("cursors reset after drop + re-create", read.cursors().isEmpty());
        assertEquals(state.lastCheckCompleted(), read.lastCheckCompleted());
    }

    @Test
    public void tst0502_freshInstallDoesNotBumpTheGenerationButALostFileWithStateDoes() {
        android.content.SharedPreferences runtime = context.getSharedPreferences(StorageFactory.RUNTIME_PREFS,
                Context.MODE_PRIVATE);
        CacheGeneration generation = new CacheGeneration(runtime);
        CacheDatabase fresh = StorageTestSupport.cacheDatabase(context);
        fresh.getWritableDatabase();
        fresh.close();
        assertEquals("fresh install: no double initial fill (IR-20)", 0L, generation.current());

        StorageFactory factory = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger());
        factory.monitorState().put(app.lightscout.domain.monitor.MonitorState.initial());
        assertTrue(context.deleteDatabase(CacheSchema.DB_NAME));
        CacheDatabase recreated = StorageTestSupport.cacheDatabase(context);
        recreated.getWritableDatabase();
        recreated.close();
        assertEquals("db lost while runtime state survived", 1L, generation.current());
    }

    @Test
    public void tst0502_cacheMigrationNeverTouchesSettingsOrConnection() {
        StorageFactory factory = new StorageFactory(context, new StorageTestSupport.TestClock(),
                new StorageTestSupport.TestLogger());
        factory.settings().put(new app.lightscout.domain.settings.Settings(true, true, 75, 55, true, 240, 25,
                15, 15, 60, 30, 3, 10, new app.lightscout.domain.settings.TargetRange(70, 180)));
        createForeignDatabase(9);
        assertFreshEmptyCache();
        assertEquals(75, factory.settings().get().lowMgdl());
        List<String> prefs = List.of(new File(context.getDataDir(), "shared_prefs").list());
        assertTrue(prefs.contains("settings.xml"));
    }
}
