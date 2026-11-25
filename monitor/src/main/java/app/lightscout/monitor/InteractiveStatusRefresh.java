package app.lightscout.monitor;

import java.util.Objects;

/** Minute refresh gate for S-10 while the display is interactive (CP-00A R-37). */
final class InteractiveStatusRefresh {

    static final long INTERVAL_MS = 60_000L;

    interface Timer {
        void schedule(Runnable task, long delayMs);
        void cancel(Runnable task);
    }

    private final Timer timer;
    private final Runnable refresh;
    private final Runnable tick = this::onTick;
    private boolean started;
    private boolean interactive;

    InteractiveStatusRefresh(Timer timer, Runnable refresh) {
        this.timer = Objects.requireNonNull(timer, "timer");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
    }

    void start(boolean isInteractive) {
        started = true;
        if (isInteractive) {
            interactive = true;
            scheduleNext();
        }
    }

    void screenOn() {
        if (!started) {
            started = true;
        }
        interactive = true;
        timer.cancel(tick);
        refresh.run();
        scheduleNext();
    }

    void screenOff() {
        interactive = false;
        timer.cancel(tick);
    }

    private void onTick() {
        if (!interactive) {
            return;
        }
        refresh.run();
        scheduleNext();
    }

    private void scheduleNext() {
        timer.cancel(tick);
        timer.schedule(tick, INTERVAL_MS);
    }
}
