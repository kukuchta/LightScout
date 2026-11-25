package app.lightscout.storage;

import static app.lightscout.storage.StorageTestSupport.reading;
import static app.lightscout.storage.StorageTestSupport.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.StorageException;

/**
 * IF-08 on a real SQLite: TST-05-03 upsert, -04 replaceWindow, -05 queries, -06 eviction, -07 clear,
 * -08 SQL injection.
 *
 * <p>Device: RD-2 (API 33) over USB — batched pre-M1 run (CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class ReadingStoreTest {

    private Context context;
    private StorageTestSupport.TestClock clock;
    private StorageTestSupport.TestLogger logger;
    private ReadingStore store;

    @Before
    public void setUp() {
        context = StorageTestSupport.context();
        StorageTestSupport.wipe(context);
        clock = new StorageTestSupport.TestClock();
        logger = new StorageTestSupport.TestLogger();
        store = new StorageFactory(context, clock, logger).readings();
    }

    @After
    public void tearDown() {
        StorageTestSupport.wipe(context);
    }

    private long fetchedAt(String id) {
        try (Cursor c = StorageTestSupport.cacheDatabase(context).getReadableDatabase()
                .rawQuery("SELECT fetched_at_ms FROM readings WHERE id = ?", new String[] {id})) {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        }
    }

    @Test
    public void tst0503_upsertSameIdUpdatesValuesAndKeepsFirstFetchedAt() {
        clock.now = t(0);
        store.upsert(List.of(reading("r1", 0, 100)));
        clock.now = t(5);
        store.upsert(List.of(new Reading("r1", t(0), 120, Trend.SINGLE_UP, "fake-cgm-2")));
        List<Reading> all = store.range(t(-60), t(60));
        assertEquals(List.of(new Reading("r1", t(0), 120, Trend.SINGLE_UP, "fake-cgm-2")), all);
        assertEquals(t(0).toEpochMilli(), fetchedAt("r1"));
    }

    @Test
    public void tst0503_unknownTrendAndNullDeviceRoundTrip() {
        Reading r = new Reading("r1", t(0), 100, Trend.UNKNOWN, null);
        store.upsert(List.of(r));
        assertEquals(List.of(r), store.range(t(0), t(1)));
    }

    @Test
    public void tst0504_replaceWindowDeletesMissingUpsertsOthersAndLeavesOutsideUntouched() {
        store.upsert(List.of(reading("before", -61, 90), reading("in-kept", -30, 100),
                reading("in-gone", -20, 110), reading("at-to", 0, 120)));
        store.replaceWindow(t(-60), t(0), List.of(reading("in-kept", -30, 105), reading("in-new", -10, 130)));
        List<Reading> all = store.range(t(-120), t(60));
        List<String> ids = new ArrayList<>();
        for (Reading r : all) {
            ids.add(r.id());
        }
        assertEquals(List.of("before", "in-kept", "in-new", "at-to"), ids);
        assertEquals(105, all.get(1).sgv());
    }

    @Test
    public void tst0505_rangeIsTimeOrderedAndHalfOpen() {
        store.upsert(List.of(reading("c", 10, 1), reading("a", 0, 1), reading("b", 5, 1), reading("d", 15, 1)));
        List<Reading> r = store.range(t(0), t(15));
        assertEquals(3, r.size());
        assertEquals("a", r.get(0).id());
        assertEquals("b", r.get(1).id());
        assertEquals("c", r.get(2).id());
    }

    @Test
    public void tst0505_latestNotAfterIgnoresAFutureDatedReading() {
        clock.now = t(0);
        store.upsert(List.of(reading("now", 0, 100), reading("future", 11, 60)));
        assertEquals("future-dated readings are stored (M-9)", 2, store.range(t(-1), t(60)).size());
        assertEquals(Optional.of(reading("now", 0, 100)), store.latestNotAfter(t(10)));
        assertEquals(Optional.empty(), store.latestNotAfter(t(-1)));
    }

    @Test
    public void tst0506_evictBeforeRemovesOlderRowsOnly() {
        store.upsert(List.of(reading("old", -44_641, 100), reading("edge", -44_640, 100), reading("new", 0, 100)));
        store.evictBefore(t(-44_640));
        assertEquals(List.of("edge", "new"), List.of(store.range(t(-50_000), t(1)).get(0).id(),
                store.range(t(-50_000), t(1)).get(1).id()));
        assertEquals(2, store.range(t(-50_000), t(1)).size());
    }

    @Test
    public void tst0507_clearEmptiesTheTable() {
        store.upsert(List.of(reading("a", 0, 100), reading("b", 5, 100)));
        store.clear();
        assertEquals(List.of(), store.range(t(-60), t(60)));
        assertEquals(Optional.empty(), store.latestNotAfter(t(60)));
    }

    @Test
    public void tst0508_idsWithQuotesSemicolonsAndCommentsRoundTripVerbatim() {
        String id = "x'); DROP TABLE readings; --";
        Reading r = new Reading(id, t(0), 100, Trend.FLAT, "dev'ice;--");
        store.upsert(List.of(r));
        store.replaceWindow(t(-1), t(1), List.of(r));
        assertEquals(List.of(r), store.range(t(-1), t(1)));
    }

    @Test
    public void unopenableDatabaseFailsWithStorageExceptionAndLogsTheOp() {
        // a directory in place of the database file cannot be opened
        File dbPath = context.getDatabasePath(CacheSchema.DB_NAME);
        assertTrue(dbPath.mkdirs());
        StorageException e = assertThrows(StorageException.class, () -> store.range(t(0), t(1)));
        assertEquals("readings.range", e.op());
        assertTrue(logger.lines.contains("STORAGE_ERROR:readings.range"));
        assertTrue(dbPath.delete());
    }
}
