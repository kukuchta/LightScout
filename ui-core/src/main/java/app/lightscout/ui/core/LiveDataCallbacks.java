package app.lightscout.ui.core;

import androidx.lifecycle.MutableLiveData;

import java.util.Objects;

import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Result;

/**
 * {@code Callback<T>} → {@code LiveData} adapter (CP-06 §8.4; 03 §4, ADR-023, ADR-024). Use cases call back on
 * {@code ioExecutor}; the adapter publishes the result with {@code postValue}, so observers receive it on the main
 * thread and no disk or network work happens there.
 */
public final class LiveDataCallbacks {

    private LiveDataCallbacks() {
    }

    /**
     * Returns a callback that posts every result into the given LiveData.
     *
     * @param target the ViewModel's LiveData
     * @param <T>    result value type
     * @return a callback usable from any thread
     */
    public static <T> Callback<T> postingTo(MutableLiveData<Result<T>> target) {
        Objects.requireNonNull(target, "target");
        return target::postValue;
    }
}
