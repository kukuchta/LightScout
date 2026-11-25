package app.lightscout.storage;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.port.DeviceStatusStore;

/**
 * IF-10 {@link DeviceStatusStore} over the ST-01 {@code device_status} and {@code profile} tables.
 *
 * <p>Device status rows are upserted by id; which rows to keep for the current IOB (SY-5) is decided by the
 * caller through {@link #evictBefore(Instant)}. The profile table holds the latest profile only:
 * {@link #saveProfile(Profile)} replaces it.
 */
final class SqliteDeviceStatusStore implements DeviceStatusStore {

    private static final String UPSERT =
            "INSERT OR REPLACE INTO device_status (id, time_ms, iob_centi, device) VALUES (?, ?, ?, ?)";
    private static final String LATEST =
            "SELECT id, time_ms, iob_centi, device FROM device_status ORDER BY time_ms DESC, id DESC LIMIT 1";
    private static final String INSERT_PROFILE =
            "INSERT INTO profile (id, start_ms, timezone, basal_json) VALUES (?, ?, ?, ?)";
    private static final String LATEST_PROFILE =
            "SELECT id, start_ms, timezone, basal_json FROM profile ORDER BY start_ms DESC, id DESC LIMIT 1";
    private static final String DELETE_BEFORE = "DELETE FROM device_status WHERE time_ms < ?";
    private static final String DELETE_ALL_STATUS = "DELETE FROM device_status";
    private static final String DELETE_ALL_PROFILES = "DELETE FROM profile";

    private static final String OP_UPSERT = "device_status.upsert";
    private static final String OP_LATEST = "device_status.latest";
    private static final String OP_SAVE_PROFILE = "profile.save";
    private static final String OP_LATEST_PROFILE = "profile.latest";
    private static final String OP_EVICT = "device_status.evictBefore";
    private static final String OP_CLEAR = "device_status.clear";

    private final SQLiteOpenHelper helper;
    private final StoreGuard guard;

    /**
     * @param helper the shared {@link CacheDatabase}
     * @param guard  failure logging and mapping (R-20)
     */
    SqliteDeviceStatusStore(SQLiteOpenHelper helper, StoreGuard guard) {
        this.helper = helper;
        this.guard = guard;
    }

    @Override
    public void upsert(List<DeviceStatus> d) {
        if (d.isEmpty()) {
            return;
        }
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try (SQLiteStatement upsert = db.compileStatement(UPSERT)) {
                for (DeviceStatus status : d) {
                    upsert.clearBindings();
                    upsert.bindString(1, status.id());
                    upsert.bindLong(2, status.time().toEpochMilli());
                    Sql.bindInt(upsert, 3, status.iobCenti());
                    Sql.bindText(upsert, 4, status.device());
                    upsert.executeInsert();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException e) {
            throw guard.fail(OP_UPSERT, e);
        }
    }

    @Override
    public Optional<DeviceStatus> latest() {
        try (Cursor c = helper.getReadableDatabase().rawQuery(LATEST, null)) {
            if (!c.moveToFirst()) {
                return Optional.empty();
            }
            return Optional.of(new DeviceStatus(c.getString(0), Instant.ofEpochMilli(c.getLong(1)),
                    Sql.nullableInt(c, 2), Sql.nullableText(c, 3)));
        } catch (RuntimeException e) {
            throw guard.fail(OP_LATEST, e);
        }
    }

    @Override
    public void saveProfile(Profile p) {
        try {
            String basalJson = CacheCodec.basalJson(p.basal());
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try (SQLiteStatement insert = db.compileStatement(INSERT_PROFILE)) {
                db.execSQL(DELETE_ALL_PROFILES);
                insert.bindString(1, p.id());
                insert.bindLong(2, p.start().toEpochMilli());
                Sql.bindText(insert, 3, CacheCodec.timezone(p.zone()));
                insert.bindString(4, basalJson);
                insert.executeInsert();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException e) {
            throw guard.fail(OP_SAVE_PROFILE, e);
        }
    }

    @Override
    public Optional<Profile> latestProfile() {
        try (Cursor c = helper.getReadableDatabase().rawQuery(LATEST_PROFILE, null)) {
            if (!c.moveToFirst()) {
                return Optional.empty();
            }
            return Optional.of(new Profile(c.getString(0), Instant.ofEpochMilli(c.getLong(1)),
                    CacheCodec.timezone(Sql.nullableText(c, 2)), CacheCodec.basal(c.getString(3))));
        } catch (RuntimeException e) {
            throw guard.fail(OP_LATEST_PROFILE, e);
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
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL(DELETE_ALL_STATUS);
                db.execSQL(DELETE_ALL_PROFILES);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException e) {
            throw guard.fail(OP_CLEAR, e);
        }
    }
}
