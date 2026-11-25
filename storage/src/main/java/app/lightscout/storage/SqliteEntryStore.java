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

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EntryStore;

/**
 * IF-09 {@link EntryStore} over the ST-01 {@code entries} table (treatments and meter BG readings).
 *
 * <p>Upsert by id with first-seen {@code fetched_at_ms} (UG-14). Split uploader documents arrive as two entries
 * {@code {id}#carbs} / {@code {id}#insulin} and are stored as separate rows. {@link #replaceWindow} is
 * origin-scoped (CP-00A R-23): it deletes only rows of the given origin in {@code [from, to)} that are missing
 * from the complete server set. There is no outbox (CON-06): entries enter only after the server confirmed them.
 */
final class SqliteEntryStore implements EntryStore {

    private static final String COLUMNS = "id, origin, type, time_ms, glucose, carbs, insulin_centi,"
            + " insulin_milli, basal_rate_milli, duration_min, text, entered_by, app, event_type_raw, is_own,"
            + " srv_modified_ms";
    private static final String UPDATE = "UPDATE entries SET origin = ?, type = ?, time_ms = ?, glucose = ?,"
            + " carbs = ?, insulin_centi = ?, insulin_milli = ?, basal_rate_milli = ?, duration_min = ?, text = ?,"
            + " entered_by = ?, app = ?, event_type_raw = ?, is_own = ?, srv_modified_ms = ? WHERE id = ?";
    private static final String INSERT = "INSERT INTO entries (origin, type, time_ms, glucose, carbs,"
            + " insulin_centi, insulin_milli, basal_rate_milli, duration_min, text, entered_by, app, event_type_raw,"
            + " is_own, srv_modified_ms, id, fetched_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String RANGE =
            "SELECT " + COLUMNS + " FROM entries WHERE time_ms >= ? AND time_ms < ? ORDER BY time_ms, id";
    private static final String IDS_IN_WINDOW =
            "SELECT id FROM entries WHERE origin = ? AND time_ms >= ? AND time_ms < ?";
    private static final String GET = "SELECT " + COLUMNS + " FROM entries WHERE id = ?";
    private static final String LATEST_OF_TYPE =
            "SELECT " + COLUMNS + " FROM entries WHERE type = ? ORDER BY time_ms DESC, id DESC LIMIT 1";
    private static final String DELETE_ID = "DELETE FROM entries WHERE id = ?";
    private static final String DELETE_BEFORE = "DELETE FROM entries WHERE time_ms < ?";
    private static final String DELETE_ALL = "DELETE FROM entries";

    /** Number of bound columns shared by UPDATE and INSERT before the id (UPDATE ... WHERE id = ?). */
    private static final int VALUE_COLUMNS = 15;

    private static final String OP_UPSERT = "entries.upsert";
    private static final String OP_REPLACE = "entries.replaceWindow";
    private static final String OP_RANGE = "entries.range";
    private static final String OP_GET = "entries.get";
    private static final String OP_LATEST = "entries.latestOfType";
    private static final String OP_DELETE = "entries.delete";
    private static final String OP_EVICT = "entries.evictBefore";
    private static final String OP_CLEAR = "entries.clear";

    private final SQLiteOpenHelper helper;
    private final Clock clock;
    private final StoreGuard guard;

    /**
     * @param helper the shared {@link CacheDatabase}
     * @param clock  IF-15, source of {@code fetched_at_ms}
     * @param guard  failure logging and mapping (R-20)
     */
    SqliteEntryStore(SQLiteOpenHelper helper, Clock clock, StoreGuard guard) {
        this.helper = helper;
        this.clock = clock;
        this.guard = guard;
    }

    @Override
    public void upsert(List<Entry> e) {
        if (e.isEmpty()) {
            return;
        }
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                upsertAll(db, e);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException ex) {
            throw guard.fail(OP_UPSERT, ex);
        }
    }

    @Override
    public void replaceWindow(EntryOrigin origin, Instant from, Instant to, List<Entry> completeServerSet) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                Set<String> keep = new HashSet<>();
                for (Entry entry : completeServerSet) {
                    keep.add(entry.id());
                }
                List<String> stale = new ArrayList<>();
                String[] args = {CacheCodec.origin(origin), Sql.arg(from), Sql.arg(to)};
                try (Cursor c = db.rawQuery(IDS_IN_WINDOW, args)) {
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
    public List<Entry> range(Instant from, Instant to) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(RANGE,
                new String[] {Sql.arg(from), Sql.arg(to)})) {
            List<Entry> result = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                result.add(read(c));
            }
            return result;
        } catch (RuntimeException e) {
            throw guard.fail(OP_RANGE, e);
        }
    }

    @Override
    public Optional<Entry> get(String id) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(GET, new String[] {id})) {
            return c.moveToFirst() ? Optional.of(read(c)) : Optional.empty();
        } catch (RuntimeException e) {
            throw guard.fail(OP_GET, e);
        }
    }

    @Override
    public Optional<Entry> latestOfType(EntryType t) {
        try (Cursor c = helper.getReadableDatabase().rawQuery(LATEST_OF_TYPE,
                new String[] {CacheCodec.entryType(t)})) {
            return c.moveToFirst() ? Optional.of(read(c)) : Optional.empty();
        } catch (RuntimeException e) {
            throw guard.fail(OP_LATEST, e);
        }
    }

    @Override
    public void delete(String id) {
        try (SQLiteStatement delete = helper.getWritableDatabase().compileStatement(DELETE_ID)) {
            delete.bindString(1, id);
            delete.executeUpdateDelete();
        } catch (RuntimeException e) {
            throw guard.fail(OP_DELETE, e);
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

    private void upsertAll(SQLiteDatabase db, List<Entry> entries) {
        long fetchedAt = clock.now().toEpochMilli();
        try (SQLiteStatement update = db.compileStatement(UPDATE);
             SQLiteStatement insert = db.compileStatement(INSERT)) {
            for (Entry entry : entries) {
                update.clearBindings();
                bindValues(update, entry);
                update.bindString(VALUE_COLUMNS + 1, entry.id());
                if (update.executeUpdateDelete() == 0) {
                    insert.clearBindings();
                    bindValues(insert, entry);
                    insert.bindString(VALUE_COLUMNS + 1, entry.id());
                    insert.bindLong(VALUE_COLUMNS + 2, fetchedAt);
                    insert.executeInsert();
                }
            }
        }
    }

    /** Binds parameters 1…15 in the column order shared by {@link #UPDATE} and {@link #INSERT}. */
    private static void bindValues(SQLiteStatement s, Entry e) {
        s.bindString(1, CacheCodec.origin(e.origin()));
        s.bindString(2, CacheCodec.entryType(e.type()));
        s.bindLong(3, e.time().toEpochMilli());
        Sql.bindInt(s, 4, e.glucose());
        Sql.bindInt(s, 5, e.carbs());
        Sql.bindInt(s, 6, e.insulinCenti());
        Sql.bindLong(s, 7, e.insulinMilli());
        Sql.bindInt(s, 8, e.basalRateMilli());
        Sql.bindInt(s, 9, e.durationMin());
        Sql.bindText(s, 10, e.text());
        Sql.bindText(s, 11, e.enteredBy());
        Sql.bindText(s, 12, e.app());
        Sql.bindText(s, 13, e.eventTypeRaw());
        s.bindLong(14, e.own() ? 1 : 0);
        Sql.bindLong(s, VALUE_COLUMNS, e.srvModifiedMs());
    }

    private static Entry read(Cursor c) {
        return new Entry(c.getString(0), CacheCodec.origin(c.getString(1)), CacheCodec.entryType(c.getString(2)),
                Instant.ofEpochMilli(c.getLong(3)), Sql.nullableInt(c, 4), Sql.nullableInt(c, 5),
                Sql.nullableInt(c, 6), Sql.nullableLong(c, 7), Sql.nullableInt(c, 8), Sql.nullableInt(c, 9),
                Sql.nullableText(c, 10), Sql.nullableText(c, 11), Sql.nullableText(c, 12), Sql.nullableText(c, 13),
                c.getInt(14) != 0, Sql.nullableLong(c, 15));
    }
}
