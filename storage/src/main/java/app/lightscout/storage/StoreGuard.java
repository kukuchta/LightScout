package app.lightscout.storage;

import android.content.SharedPreferences;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.StorageException;

/**
 * Turns a store failure into the {@link StorageException} of CP-00A R-20 after logging
 * {@code STORAGE_ERROR:<op>} once (R-13: the component where the event happens logs it).
 *
 * <p>{@code op} values are constants of the store classes (fixed vocabulary, SR-07); no value, id or text of the
 * failing data is ever logged.
 */
final class StoreGuard {

    private final Logger logger;

    /**
     * @param logger IF-66 event logger
     */
    StoreGuard(Logger logger) {
        this.logger = logger;
    }

    /**
     * Logs {@code STORAGE_ERROR:<op>} and returns the exception to throw.
     *
     * @param op    constant operation name, e.g. {@code readings.upsert}
     * @param cause the underlying failure; may be null (e.g. a failed {@code commit()})
     * @return the exception the caller throws
     */
    StorageException fail(String op, Throwable cause) {
        logger.event(EventCode.STORAGE_ERROR, op);
        return new StorageException(op, cause);
    }

    /**
     * Commits an edit synchronously (ST-04 rule: {@code commit()}, never {@code apply()}).
     *
     * @param editor the prepared edit
     * @return {@code true} when the write reached the disk
     */
    @SuppressWarnings("ApplySharedPref") // commit() is required: callers need the result before they continue
    static boolean commit(SharedPreferences.Editor editor) {
        return editor.commit();
    }
}
