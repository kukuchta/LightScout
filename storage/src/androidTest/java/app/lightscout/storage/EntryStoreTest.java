package app.lightscout.storage;

import static app.lightscout.storage.StorageTestSupport.entry;
import static app.lightscout.storage.StorageTestSupport.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.EntryStore;

/**
 * IF-09 on a real SQLite: TST-05-03 upsert + split ids, -04 origin-scoped replaceWindow (R-23), -05 queries,
 * -06 eviction, -07 clear, -08 SQL injection.
 *
 * <p>Device: RD-2 (API 33) over USB — batched pre-M1 run (CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class EntryStoreTest {

    private Context context;
    private StorageTestSupport.TestClock clock;
    private EntryStore store;

    @Before
    public void setUp() {
        context = StorageTestSupport.context();
        StorageTestSupport.wipe(context);
        clock = new StorageTestSupport.TestClock();
        store = new StorageFactory(context, clock, new StorageTestSupport.TestLogger()).entries();
    }

    @After
    public void tearDown() {
        StorageTestSupport.wipe(context);
    }

    private static List<String> ids(List<Entry> entries) {
        List<String> ids = new ArrayList<>();
        for (Entry e : entries) {
            ids.add(e.id());
        }
        return ids;
    }

    private long fetchedAt(String id) {
        try (Cursor c = StorageTestSupport.cacheDatabase(context).getReadableDatabase()
                .rawQuery("SELECT fetched_at_ms FROM entries WHERE id = ?", new String[] {id})) {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        }
    }

    @Test
    public void tst0503_allFieldsRoundTripAndUpsertKeepsFirstFetchedAt() {
        Entry full = new Entry("own-1", EntryOrigin.TREATMENTS, EntryType.TEMP_BASAL, t(0), 110, 20, 125, 850, 30,
                "pump text", "LightScout", "LightScout", "Temp Basal", true, 1_759_320_000_123L);
        clock.now = t(0);
        store.upsert(List.of(full));
        assertEquals(Optional.of(full), store.get("own-1"));

        Entry changed = new Entry("own-1", EntryOrigin.TREATMENTS, EntryType.TEMP_BASAL, t(1), null, null, null,
                null, null, null, null, null, null, false, null);
        clock.now = t(9);
        store.upsert(List.of(changed));
        assertEquals(Optional.of(changed), store.get("own-1"));
        assertEquals(t(0).toEpochMilli(), fetchedAt("own-1"));
    }

    @Test
    public void tst0503_splitIdsAreSeparateRows() {
        Entry carbs = new Entry("doc1#carbs", EntryOrigin.TREATMENTS, EntryType.CARBS, t(0), null, 30, null, null,
                null, null, "fake-uploader", null, "Meal Bolus", false, null);
        Entry insulin = new Entry("doc1#insulin", EntryOrigin.TREATMENTS, EntryType.BOLUS, t(0), null, null, 350,
                null, null, null, "fake-uploader", null, "Meal Bolus", false, null);
        store.upsert(List.of(carbs, insulin));
        assertEquals(Optional.of(carbs), store.get("doc1#carbs"));
        assertEquals(Optional.of(insulin), store.get("doc1#insulin"));
        assertEquals(2, store.range(t(-1), t(1)).size());
    }

    @Test
    public void importedMilliDoseRoundTripsAlongsideUnchangedCentiValue() {
        Entry imported = new Entry("precise", EntryOrigin.TREATMENTS, EntryType.BOLUS, t(0),
                null, null, 237, 2375L, null, null, null, "uploader", null, "Bolus", false, null);
        store.upsert(List.of(imported));
        assertEquals(Optional.of(imported), store.get("precise"));
    }

    @Test
    public void manualMilliUpsertReopenUpdateAndSyncReplacementAreExact() {
        CacheDatabase database = StorageTestSupport.cacheDatabase(context);
        StoreGuard guard = new StoreGuard(new StorageTestSupport.TestLogger());
        store = new SqliteEntryStore(database, clock, guard);
        int[] doses = {25, 1234, 25000};
        List<Entry> initial = new ArrayList<>();
        for (int dose : doses) {
            initial.add(new Entry("manual-" + dose, EntryOrigin.TREATMENTS, EntryType.BOLUS, t(0),
                    null, null, (dose + 5) / 10, (long) dose, null, null, null,
                    "LightScout", "LightScout", "Correction Bolus", true, 1000L));
        }
        clock.now = t(0);
        store.upsert(initial);
        database.close();
        for (Entry expected : initial) {
            assertEquals(Optional.of(expected), store.get(expected.id()));
        }
        List<Entry> changed = new ArrayList<>();
        for (int i = 0; i < doses.length; i++) {
            int dose = doses[(i + 1) % doses.length];
            changed.add(new Entry(initial.get(i).id(), EntryOrigin.TREATMENTS, EntryType.BOLUS, t(1),
                    null, null, (dose + 5) / 10, (long) dose, null, null, null,
                    "LightScout", "LightScout", "Correction Bolus", true, 2000L));
        }
        clock.now = t(9);
        store.upsert(changed);
        store.replaceWindow(EntryOrigin.TREATMENTS, t(-1), t(2), changed);
        database.close();
        for (Entry expected : changed) {
            assertEquals(Optional.of(expected), store.get(expected.id()));
            assertEquals(t(0).toEpochMilli(), fetchedAt(expected.id()));
        }
        database.close();
    }

    @Test
    public void v1MigrationPreservesRowsAndLegacyCentiValueFallsBackToMilli() {
        SQLiteDatabase legacy = context.openOrCreateDatabase(CacheSchema.DB_NAME, Context.MODE_PRIVATE, null);
        legacy.execSQL("CREATE TABLE entries ("
                + "id TEXT PRIMARY KEY, origin TEXT NOT NULL, type TEXT NOT NULL, time_ms INTEGER NOT NULL,"
                + "glucose INTEGER, carbs INTEGER, insulin_centi INTEGER, basal_rate_milli INTEGER,"
                + "duration_min INTEGER, text TEXT, entered_by TEXT, app TEXT, event_type_raw TEXT,"
                + "is_own INTEGER NOT NULL DEFAULT 0, srv_modified_ms INTEGER, fetched_at_ms INTEGER NOT NULL)");
        legacy.execSQL("INSERT INTO entries (id, origin, type, time_ms, insulin_centi, text, entered_by,"
                + " event_type_raw, fetched_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[] {"legacy", "treatments", "BOLUS", t(0).toEpochMilli(), 237, "note", "uploader",
                        "Bolus", 123L});
        legacy.setVersion(1);
        legacy.close();

        Entry legacyValue = store.get("legacy").orElseThrow();
        assertEquals(Integer.valueOf(237), legacyValue.insulinCenti());
        assertEquals(Long.valueOf(2370), legacyValue.insulinMilli());
        assertEquals("note", legacyValue.text());
        assertEquals(123L, fetchedAt("legacy"));

        Entry precise = new Entry("legacy", EntryOrigin.TREATMENTS, EntryType.BOLUS, t(0),
                null, null, 237, 2375L, null, null, "note", "uploader", null, "Bolus", false, null);
        store.upsert(List.of(precise));
        assertEquals(Optional.of(precise), store.get("legacy"));
        assertEquals(123L, fetchedAt("legacy"));
    }

    @Test
    public void tst0504_replaceWindowDeletesOnlyMissingRowsOfThatOriginInsideTheWindow() {
        store.upsert(List.of(
                entry("t-before", EntryOrigin.TREATMENTS, EntryType.CARBS, -61),
                entry("t-kept", EntryOrigin.TREATMENTS, EntryType.CARBS, -30),
                entry("t-gone", EntryOrigin.TREATMENTS, EntryType.BOLUS, -20),
                entry("mbg-in", EntryOrigin.ENTRIES_MBG, EntryType.BG_CHECK, -25),
                entry("t-at-to", EntryOrigin.TREATMENTS, EntryType.CARBS, 0)));
        store.replaceWindow(EntryOrigin.TREATMENTS, t(-60), t(0), List.of(
                entry("t-kept", EntryOrigin.TREATMENTS, EntryType.CARBS, -30),
                entry("t-new", EntryOrigin.TREATMENTS, EntryType.SITE_CHANGE, -5)));
        assertEquals(List.of("t-before", "t-kept", "mbg-in", "t-new", "t-at-to"),
                ids(store.range(t(-120), t(60))));
    }

    @Test
    public void tst0504_mbgWindowLeavesTreatmentsUntouched() {
        store.upsert(List.of(entry("t-in", EntryOrigin.TREATMENTS, EntryType.CARBS, -30),
                entry("mbg-gone", EntryOrigin.ENTRIES_MBG, EntryType.BG_CHECK, -20)));
        store.replaceWindow(EntryOrigin.ENTRIES_MBG, t(-60), t(0), List.of());
        assertEquals(List.of("t-in"), ids(store.range(t(-120), t(60))));
    }

    @Test
    public void tst0505_rangeOrderedLatestOfTypeGetAndDelete() {
        store.upsert(List.of(
                entry("site-old", EntryOrigin.TREATMENTS, EntryType.SITE_CHANGE, -4000),
                entry("site-new", EntryOrigin.TREATMENTS, EntryType.SITE_CHANGE, -100),
                entry("sensor", EntryOrigin.TREATMENTS, EntryType.SENSOR_CHANGE, -50),
                entry("carbs", EntryOrigin.TREATMENTS, EntryType.CARBS, -10)));
        assertEquals(List.of("site-new", "sensor", "carbs"), ids(store.range(t(-200), t(0))));
        assertEquals("site-new", store.latestOfType(EntryType.SITE_CHANGE).orElseThrow().id());
        assertEquals(Optional.empty(), store.latestOfType(EntryType.PUMP_ALERT));
        assertEquals("sensor", store.get("sensor").orElseThrow().id());
        store.delete("sensor");
        assertEquals(Optional.empty(), store.get("sensor"));
        store.delete("does-not-exist");
        assertEquals(3, store.range(t(-5000), t(0)).size());
    }

    @Test
    public void tst0506_evictBeforeRemovesOlderRowsOnly() {
        store.upsert(List.of(entry("old", EntryOrigin.TREATMENTS, EntryType.CARBS, -44_641),
                entry("new", EntryOrigin.ENTRIES_MBG, EntryType.BG_CHECK, -44_640)));
        store.evictBefore(t(-44_640));
        assertEquals(List.of("new"), ids(store.range(t(-50_000), t(1))));
    }

    @Test
    public void tst0507_clearEmptiesTheTable() {
        store.upsert(List.of(entry("a", EntryOrigin.TREATMENTS, EntryType.CARBS, 0)));
        store.clear();
        assertEquals(List.of(), store.range(t(-60), t(60)));
        assertEquals(Optional.empty(), store.get("a"));
    }

    @Test
    public void tst0508_idsAndTextWithQuotesSemicolonsAndCommentsRoundTripVerbatim() {
        Entry e = new Entry("id'; DELETE FROM entries; --", EntryOrigin.TREATMENTS, EntryType.PUMP_ALERT, t(0),
                null, null, null, null, null, "O'Brien; -- \"alert\"", "x'--", "a;b", "Announcement'", false, null);
        store.upsert(List.of(e));
        assertEquals(Optional.of(e), store.get("id'; DELETE FROM entries; --"));
        store.delete("id'; DELETE FROM entries; --");
        assertEquals(Optional.empty(), store.get(e.id()));
    }
}
