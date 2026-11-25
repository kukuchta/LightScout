package app.lightscout.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * CMP-23 CacheDatabase: {@code cache.db} in WAL mode (ADR-024) in the default credential-encrypted database
 * directory (SR-04), no app-level encryption (ADR-054).
 *
 * <p>Migration policy ADR-033: the cache is disposable (Nightscout is the source of truth), so unknown version
 * changes and downgrades may recreate the cache. The v1-to-v2 insulin precision migration is deliberately
 * additive: it preserves every existing row and leaves old centi-only values readable.
 * A re-creation over possibly stale runtime state also bumps the {@link CacheGeneration}, so that the sync cursors
 * in {@code MonitorState} are reset and the cache is refetched in full (A-5 ruling: dropping tables alone would
 * leave stale cursors).
 * The file is opened lazily on the first store call, which must happen after unlock and off the main thread.
 */
final class CacheDatabase extends SQLiteOpenHelper {

    private static final String MIGRATE_V1_TO_V2 = CacheSchema.MIGRATE_V1_TO_V2;
    private final CacheGeneration generation;

    /**
     * @param context    application context (credential-encrypted storage)
     * @param generation re-creation counter in ST-04
     */
    CacheDatabase(Context context, CacheGeneration generation) {
        super(context, CacheSchema.DB_NAME, null, CacheSchema.VERSION);
        this.generation = generation;
        setWriteAheadLoggingEnabled(true);
    }

    /**
     * Creates the cache. The generation is bumped only if a stored {@code MonitorState} already exists (the file
     * was lost or was unversioned while runtime state survived); on a fresh install there are no cursors to reset,
     * so the first fill does not run twice (IR-20).
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        dropAndCreate(db);
        generation.bumpIfMonitorStateExists();
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL(MIGRATE_V1_TO_V2);
            return;
        }
        dropAndCreate(db);
        generation.bump();
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        dropAndCreate(db);
        generation.bump();
    }

    private static void dropAndCreate(SQLiteDatabase db) {
        for (String table : CacheSchema.LEGACY_TABLES) {
            db.execSQL(CacheSchema.dropStatement(table));
        }
        for (String table : CacheSchema.TABLES) {
            db.execSQL(CacheSchema.dropStatement(table));
        }
        for (String statement : CacheSchema.CREATE_STATEMENTS) {
            db.execSQL(statement);
        }
    }
}
