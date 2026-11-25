package app.lightscout.domain.port;

/**
 * Asynchronous result receiver (03 §6.2). Fires on the calling executor (CP-00 §4).
 *
 * @param <T> type of the value on success
 */
public interface Callback<T> {

    /**
     * Receives the result.
     *
     * @param result the result, never {@code null}
     */
    void onResult(Result<T> result);
}
