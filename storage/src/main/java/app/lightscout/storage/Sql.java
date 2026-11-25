package app.lightscout.storage;

import android.database.Cursor;
import android.database.sqlite.SQLiteStatement;

import java.time.Instant;

/**
 * Bound-parameter helpers for the SQLite stores (SR-30 / M-12: values only ever reach SQL as bound
 * parameters, never through string concatenation).
 */
final class Sql {

    private Sql() {
    }

    /**
     * Binds a nullable text value.
     *
     * @param statement compiled statement
     * @param index     1-based parameter index
     * @param value     value or null
     */
    static void bindText(SQLiteStatement statement, int index, String value) {
        if (value == null) {
            statement.bindNull(index);
        } else {
            statement.bindString(index, value);
        }
    }

    /**
     * Binds a nullable integer value.
     *
     * @param statement compiled statement
     * @param index     1-based parameter index
     * @param value     value or null
     */
    static void bindLong(SQLiteStatement statement, int index, Long value) {
        if (value == null) {
            statement.bindNull(index);
        } else {
            statement.bindLong(index, value);
        }
    }

    /**
     * Binds a nullable int value.
     *
     * @param statement compiled statement
     * @param index     1-based parameter index
     * @param value     value or null
     */
    static void bindInt(SQLiteStatement statement, int index, Integer value) {
        bindLong(statement, index, value == null ? null : value.longValue());
    }

    /**
     * @param instant instant
     * @return UTC epoch milliseconds (T-1) as a query argument
     */
    static String arg(Instant instant) {
        return Long.toString(instant.toEpochMilli());
    }

    /**
     * @param cursor cursor positioned on a row
     * @param column column index
     * @return the value, or {@code null} for SQL NULL
     */
    static Integer nullableInt(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getInt(column);
    }

    /**
     * @param cursor cursor positioned on a row
     * @param column column index
     * @return the value, or {@code null} for SQL NULL
     */
    static Long nullableLong(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getLong(column);
    }

    /**
     * @param cursor cursor positioned on a row
     * @param column column index
     * @return the value, or {@code null} for SQL NULL
     */
    static String nullableText(Cursor cursor, int column) {
        return cursor.isNull(column) ? null : cursor.getString(column);
    }
}
