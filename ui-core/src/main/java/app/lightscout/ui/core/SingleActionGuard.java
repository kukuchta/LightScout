package app.lightscout.ui.core;

import android.view.View;

import java.util.Objects;

/**
 * GIR-1 single-action guard (CMP-33; CP-06 §8.3): repeated taps on an action that navigates or sends are ignored
 * while the transition / request is in progress. One guard per guarded control (or group of controls that must
 * not run concurrently); use cases also reject concurrent duplicates (03 §5.3).
 *
 * <p>Main thread only. Typical use: {@code guard.attach(saveButton, this::save)} and {@link #complete()} when the
 * result arrives (e.g. in the LiveData observer) or when the screen returns after a navigation.
 */
public final class SingleActionGuard {

    private boolean inProgress;

    /**
     * Creates an idle guard.
     */
    public SingleActionGuard() {
        // Idle until the first accepted action.
    }

    /**
     * Starts an action if none is in progress.
     *
     * @return {@code true} if the caller may run the action; {@code false} if it must be ignored
     */
    public boolean tryStart() {
        if (inProgress) {
            return false;
        }
        inProgress = true;
        return true;
    }

    /**
     * Marks the action as finished; the next tap is accepted again.
     */
    public void complete() {
        inProgress = false;
    }

    /**
     * Tells whether an action is running.
     *
     * @return {@code true} while an accepted action has not completed
     */
    public boolean isInProgress() {
        return inProgress;
    }

    /**
     * Sets a click listener on {@code view} that runs {@code action} only when {@link #tryStart()} accepts it.
     *
     * @param view   the guarded control
     * @param action the action (navigates or sends)
     */
    public void attach(View view, Runnable action) {
        Objects.requireNonNull(action, "action");
        view.setOnClickListener(v -> {
            if (tryStart()) {
                action.run();
            }
        });
    }
}
