package app.lightscout.storage;

import java.util.List;

/**
 * ST-01 schema, version 2 (v1 databases additively gain the imported insulin milli-unit value; {@code sync_meta}
 * dropped by ADR-071 / CP-00A R-21: per-collection cursors incl. {@code completeFrom} live in {@code MonitorState},
 * ST-04).
 */
final class CacheSchema {

    /** Database file name in the app's default credential-encrypted database directory (SR-04). */
    static final String DB_NAME = "cache.db";

    /** {@code PRAGMA user_version} of this schema. */
    static final int VERSION = 2;
    static final String MIGRATE_V1_TO_V2 = "ALTER TABLE entries ADD COLUMN insulin_milli INTEGER";

    static final String READINGS = "readings";
    static final String ENTRIES = "entries";
    static final String DEVICE_STATUS = "device_status";
    static final String PROFILE = "profile";

    /** Cache tables of this version. */
    static final List<String> TABLES = List.of(READINGS, ENTRIES, DEVICE_STATUS, PROFILE);

    /** Tables of earlier drafts that are dropped as well if present (ADR-071). */
    static final List<String> LEGACY_TABLES = List.of("sync_meta");

    /** Index names created by {@link #CREATE_STATEMENTS} (TST-05-01). */
    static final List<String> INDEXES = List.of("readings_time", "entries_time", "entries_type_time",
            "device_status_time");

    /** DDL statements in execution order (§8.1). */
    static final List<String> CREATE_STATEMENTS = List.of(
            "CREATE TABLE readings ("
                    + " id TEXT PRIMARY KEY,"
                    + " time_ms INTEGER NOT NULL,"
                    + " sgv INTEGER NOT NULL,"
                    + " direction TEXT,"
                    + " device TEXT,"
                    + " fetched_at_ms INTEGER NOT NULL"
                    + ")",
            "CREATE INDEX readings_time ON readings(time_ms)",
            "CREATE TABLE entries ("
                    + " id TEXT PRIMARY KEY,"
                    + " origin TEXT NOT NULL CHECK (origin IN ('treatments','entries-mbg')),"
                    + " type TEXT NOT NULL,"
                    + " time_ms INTEGER NOT NULL,"
                    + " glucose INTEGER,"
                    + " carbs INTEGER,"
                    + " insulin_centi INTEGER,"
                    + " insulin_milli INTEGER,"
                    + " basal_rate_milli INTEGER,"
                    + " duration_min INTEGER,"
                    + " text TEXT,"
                    + " entered_by TEXT,"
                    + " app TEXT,"
                    + " event_type_raw TEXT,"
                    + " is_own INTEGER NOT NULL DEFAULT 0,"
                    + " srv_modified_ms INTEGER,"
                    + " fetched_at_ms INTEGER NOT NULL"
                    + ")",
            "CREATE INDEX entries_time ON entries(time_ms)",
            "CREATE INDEX entries_type_time ON entries(type, time_ms)",
            "CREATE TABLE device_status ("
                    + " id TEXT PRIMARY KEY,"
                    + " time_ms INTEGER NOT NULL,"
                    + " iob_centi INTEGER,"
                    + " device TEXT"
                    + ")",
            "CREATE INDEX device_status_time ON device_status(time_ms)",
            "CREATE TABLE profile ("
                    + " id TEXT PRIMARY KEY,"
                    + " start_ms INTEGER NOT NULL,"
                    + " timezone TEXT,"
                    + " basal_json TEXT NOT NULL"
                    + ")");

    private CacheSchema() {
    }

    /**
     * Returns the statement that drops a table if it exists. Only the constant table names of this class are
     * accepted, so no external value can ever reach the SQL text (SR-30).
     *
     * @param table one of {@link #TABLES} or {@link #LEGACY_TABLES}
     * @return the DROP statement
     * @throws IllegalArgumentException for any other name
     */
    static String dropStatement(String table) {
        if (!TABLES.contains(table) && !LEGACY_TABLES.contains(table)) {
            throw new IllegalArgumentException("not a cache table");
        }
        return "DROP TABLE IF EXISTS " + table;
    }
}
