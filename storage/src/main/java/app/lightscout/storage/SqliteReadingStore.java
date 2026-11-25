package app.lightscout.storage;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.ReadingStore;

/**
 * IF-08 {@link ReadingStore} over the ST-01 {@code readings} table.
 *
 * <p>Upsert by id (DR-1); {@code fetched_at_ms} is the first-seen time from IF-15 {@link Clock} and is kept on
 * later upserts of the same id (UG-14, CP-00A R-39). Future-dated readings are stored (M-9); callers exclude
 * them through {@link #latestNotAfter(Instant)}. Windows are half-open {@code [from, to)}.
 */
final class SqliteReadingStore implements ReadingStore {

    private static final String COLUMNS = "id, time_ms, sgv, direction, device";
    private static final String UPDATE =
            "UPDATE readings SET time_ms = ?, sgv = ?, direction = ?, device = ? WHERE id = ?";
    private static final String INSERT =
            "INSERT INTO readings (id, time_ms, sgv, direction, device, fetched_at_ms) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String RANGE =
            "SELECT " + COLUMNS + " FROM readings WHERE time_ms >= ? AND time_ms < ? ORDER BY time_ms, id";
    private static final String IDS_IN_WINDOW = "SELECT id FROM readings WHERE time_ms >= ? AND time_ms < ?";
    private static final String LATEST_NOT_AFTER =
            "SELECT " + COLUMNS + " FROM readings WHERE time_ms <= ? ORDER BY time_ms DESC, id DESC LIMIT 1";
    private static final String DELETE_ID = "DELETE FROM readings WHERE id = ?";
    private static final String DELETE_BEFORE = "DELETE FROM readings WHERE time_ms < ?";
    private static final String DELETE_ALL = "DELETE FROM readings";

    private static final String OP_UPSERT = "readings.upsert";
    private static final String OP_RANGE = "readings.range";
    private static final String OP_LATEST = "readings.latestNotAfter";
    private static final String OP_REPLACE = "readings.replaceWindow";
    private static final String OP_EVICT = "readings.evictBefore";
    private static final String OP_CLEAR = "readings.clear";

    private final SQLiteOpenHelper helper;
    private final Clock clock;
    private final StoreGuard guard;

    /**
     * @param helper the shared {@link CacheDatabase}
     * @param clock  IF-15, source of {@code fetched_at_ms}
     * @param guard  failure logging and mapping (R-20)
     */
    SqliteReadingStore(SQLiteOpenHelper helper, Clock clock, StoreGuard guard) {
        this.helper = helper;
        this.clock = clock;
        this.guard = guard;
    }

    @Override
    public void upsert(List<Reading> r) {
        if (r.isEmpty()) {
            return;
        }
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                upsertAll(db, r);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException e) {
            throw guard.fail(OP_UPSERT, e);
        }
    }

    @Override
    public List<Reading> range(Instant from, Instant to) {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(RANGE, new String[] {Sql.arg(from), Sql.arg(to)})) {
                List<Reading> result = new ArrayList<>(c.getCount());
                while (c.moveToNext()) {
                    result.add(read(c));
                }
                return result;
            }
        } catch (RuntimeException e) {
            throw guard.fail(OP_RANGE, e);
        }
    }

    @Override
    public Optional<Reading> latestNotAfter(Instant t) {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(LATEST_NOT_AFTER, new String[] {Sql.arg(t)})) {
                return c.moveToFirst() ? Optional.of(read(c)) : Optional.empty();
            }
        } catch (RuntimeException e) {
            throw guard.fail(OP_LATEST, e);
        }
    }

    @Override
    public void replaceWindow(Instant from, Instant to, List<Reading> completeServerSet) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                Set<String> keep = new HashSet<>();
                for (Reading reading : completeServerSet) {
                    keep.add(reading.id());
                }
                List<String> stale = new ArrayList<>();
                try (Cursor c = db.rawQuery(IDS_IN_WINDOW, new String[] {Sql.arg(from), Sql.arg(to)})) {
                    while (c.moveToNext()) {
                        String id = c.getString(0);
                        if (!keep.contains(id)) {
                            stale.add(id);
                        }
                    }
                }
                try (SQLiteStatement delete = db.compileStatement(DELETE_ID)) {
                    for (String id : stale) {
                        delete.bindString(1, id);
                        delete.executeUpdateDelete();
                    }
                }
                upsertAll(db, completeServerSet);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException e) {
            throw guard.fail(OP_REPLACE, e);
        }
    }

    @Override
    public void evictBefore(Instant t) {
        try (SQLiteStatement delete = helper.getWritableDatabase().compileStatement(DELETE_BEFORE)) {
            delete.bindLong(1, t.toEpochMilli());
            delete.executeUpdateDelete();
        } catch (RuntimeException e) {
            throw guard.fail(OP_EVICT, e);
        }
    }

    @Override
    public void clear() {
        try {
            helper.getWritableDatabase().execSQL(DELETE_ALL);
        } catch (RuntimeException e) {
            throw guard.fail(OP_CLEAR, e);
        }
    }

    private void upsertAll(SQLiteDatabase db, List<Reading> readings) {
        long fetchedAt = clock.now().toEpochMilli();
        try (SQLiteStatement update = db.compileStatement(UPDATE);
             SQLiteStatement insert = db.compileStatement(INSERT)) {
            for (Reading reading : readings) {
                String direction = CacheCodec.direction(reading.trend());
                update.clearBindings();
                update.bindLong(1, reading.time().toEpochMilli());
                update.bindLong(2, reading.sgv());
                Sql.bindText(update, 3, direction);
                Sql.bindText(update, 4, reading.device());
                update.bindString(5, reading.id());
                if (update.executeUpdateDelete() == 0) {
                    insert.clearBindings();
                    insert.bindString(1, reading.id());
                    insert.bindLong(2, reading.time().toEpochMilli());
                    insert.bindLong(3, reading.sgv());
                    Sql.bindText(insert, 4, direction);
                    Sql.bindText(insert, 5, reading.device());
                    insert.bindLong(6, fetchedAt);
                    insert.executeInsert();
                }
            }
        }
    }

    private static Reading read(Cursor c) {
        return new Reading(c.getString(0), Instant.ofEpochMilli(c.getLong(1)), c.getInt(2),
                CacheCodec.trend(Sql.nullableText(c, 3)), Sql.nullableText(c, 4));
    }
}
