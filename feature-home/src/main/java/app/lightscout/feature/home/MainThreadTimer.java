package app.lightscout.feature.home;

import android.os.Handler;
import android.os.Looper;

/**
 * {@link HomeModel.Timer} on the main looper (GIR-1 fail-safes of S-01). Owned by {@link HomeViewModel}; pending tasks
 * are cancelled through {@link HomeModel#dispose()} in {@code onCleared()}.
 */
public final class MainThreadTimer implements HomeModel.Timer {

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Creates the timer on the main looper. */
    public MainThreadTimer() {
        // Handler bound to the main looper above.
    }

    @Override
    public void postDelayed(Runnable task, long delayMs) {
        handler.postDelayed(task, delayMs);
    }

    @Override
    public void cancel(Runnable task) {
        handler.removeCallbacks(task);
    }
}
