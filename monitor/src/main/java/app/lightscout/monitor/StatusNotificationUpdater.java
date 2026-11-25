package app.lightscout.monitor;

import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationManagerCompat;

import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.usecases.StatusRepository;

import java.util.concurrent.ExecutorService;

/** Bridges IF-21 snapshots to S-10 and gates minute refreshes on screen state (CP-09, R-37). */
final class StatusNotificationUpdater implements AutoCloseable {

    private final Context context;
    private final StatusRepository status;
    private final StatusNotificationBuilder builder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final InteractiveStatusRefresh refresh;
    private final StatusListener listener = this::onStatus;
    private final StatusChart chart;
    private final ExecutorService chartExecutor;
    private volatile StatusSnapshot latest;
    private volatile boolean started;

    StatusNotificationUpdater(Context context, StatusRepository status, StatusNotificationBuilder builder,
                              StatusChart chart, ExecutorService chartExecutor) {
        this.context = context.getApplicationContext();
        this.status = status;
        this.builder = builder;
        this.chart = chart;
        this.chartExecutor = chartExecutor;
        HandlerTimer timer = new HandlerTimer(main);
        this.refresh = new InteractiveStatusRefresh(timer, status::refresh);
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        status.addListener(listener);
        post(status.current());
        PowerManager power = context.getSystemService(PowerManager.class);
        refresh.start(power != null && power.isInteractive());
    }

    void screenOn() {
        refresh.screenOn();
    }

    void screenOff() {
        refresh.screenOff();
    }

    @Override
    public void close() {
        if (!started) {
            return;
        }
        started = false;
        status.removeListener(listener);
        refresh.screenOff();
        if (chartExecutor != null) {
            chartExecutor.shutdownNow();
        }
    }

    private void onStatus(StatusSnapshot snapshot) {
        main.post(() -> {
            if (started) {
                post(snapshot);
            }
        });
    }

    private void post(StatusSnapshot snapshot) {
        latest = snapshot;
        notifyNow(snapshot);
        if (chart != null && chartExecutor != null && !chartExecutor.isShutdown()) {
            // The chart is rendered off the main thread; the notification is re-posted once, when it is ready.
            chart.refresh(snapshot, () -> main.post(() -> {
                if (started) {
                    notifyNow(latest);
                }
            }));
        }
    }

    private void notifyNow(StatusSnapshot snapshot) {
        try {
            Notification notification = builder.build(snapshot);
            NotificationManagerCompat.from(context).notify(NotificationIds.STATUS, notification);
        } catch (SecurityException ignored) {
            // Notification permission can be denied without stopping monitoring.
        } catch (RuntimeException ignored) {
            // Notification permission can be denied; monitoring must continue without a surface.
        }
    }

    private static final class HandlerTimer implements InteractiveStatusRefresh.Timer {

        private final Handler handler;

        HandlerTimer(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void schedule(Runnable task, long delayMs) {
            handler.postDelayed(task, delayMs);
        }

        @Override
        public void cancel(Runnable task) {
            handler.removeCallbacks(task);
        }
    }
}
