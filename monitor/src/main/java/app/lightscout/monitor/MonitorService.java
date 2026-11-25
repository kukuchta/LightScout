package app.lightscout.monitor;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import app.lightscout.domain.port.EventCode;
import app.lightscout.platform.ChannelId;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CMP-50 {@code MonitorService} (ADR-042): the one foreground service, type {@code specialUse} with the subtype
 * property (manifest), not exported (SR-20), {@code START_STICKY}. It hosts the sync schedule; every tick,
 * evaluation and ring runs on {@code monitorExecutor} through {@link MonitorRuntime}.
 *
 * <p>F4: {@code startForeground} is called **first** in {@code onStartCommand}, with the resource small icon, before
 * the runtime (container) is touched. A refusal is logged ({@code FGS_START_DENIED:<origin>}), a retry alarm is armed
 * (heartbeat if ringing, else tick, 60 s), the service stops itself and returns {@code START_NOT_STICKY} — it never
 * continues non-foreground (🏛️ Q-66). A {@code null} intent is the {@code START_STICKY} restart → tick
 * {@code SERVICE_RESTART} (a stale {@code lastCheckCompleted} then rings NOT_RUNNING once — 08 §8.5, R-68).
 */
public final class MonitorService extends Service {

    private static volatile boolean running;

    private MonitorRuntime runtime;
    private StatusNotificationBuilder builder;
    private StatusNotificationUpdater statusUpdater;
    private ScreenStateReceiver screenReceiver;
    private boolean foreground;

    /**
     * Tells whether a service instance exists in this process (used by the JobScheduler net).
     *
     * @return {@code true} between {@code onCreate} and {@code onDestroy}
     */
    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        StartOrigin origin = MonitorIntents.origin(intent);
        MonitorRuntimeProvider provider = MonitorComponents.provider(this);
        MonitorBootDependencies boot = provider.monitorBootDependencies();
        if (!enterForeground(boot, origin)) {
            return refused(boot, origin);
        }
        if (runtime == null) {
            runtime = provider.monitorRuntime();
            if (builder == null) {
                try {
                    boot.channels().ensureChannels();
                    builder = StatusNotifications.builder(this, boot.channels(), boot.clock());
                } catch (RuntimeException ignored) {
                    // Keep the service alive after the F4 resource-icon fallback was posted.
                }
            }
            if (builder != null) {
                ExecutorService chartExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread thread = new Thread(r, "s10-chart");
                    thread.setDaemon(true);
                    return thread;
                });
                StatusChart chart = new StatusChart(this, runtime::historyLoader, boot.clock(), chartExecutor);
                builder.setChart(chart);
                statusUpdater = new StatusNotificationUpdater(this, runtime.statusRepository(), builder, chart,
                        chartExecutor);
                statusUpdater.start();
            }
            registerScreenReceiver();
        }
        if (StartDispatch.resumesAfterBoot(origin)) {
            boot.logger().event(EventCode.BOOT_RESUMED);
        }
        runtime.onServiceStarted(origin);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException e) {
                // not registered
            }
            screenReceiver = null;
        }
        if (statusUpdater != null) {
            statusUpdater.close();
            statusUpdater = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 🏛️ Q-66 / IR-72: {@code startForeground} was refused. Never continue as a non-foreground service: arm the retry
     * alarm (heartbeat if ringing, else tick, 60 s), stop, and do not ask for a sticky restart.
     */
    private int refused(MonitorBootDependencies boot, StartOrigin origin) {
        try {
            boolean ringing = runtime != null && runtime.lastKnownRinging();
            MonitorAlarms alarms = MonitorComponents.alarms(this, boot);
            if (StartDispatch.afterForegroundRefusal(origin, ringing) == StartDispatch.Retry.HEARTBEAT) {
                alarms.armHeartbeat(StartDispatch.ALARM_RETRY_DELAY);
            } else {
                alarms.armTickRetry(StartDispatch.ALARM_RETRY_DELAY);
            }
        } finally {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private boolean enterForeground(MonitorBootDependencies boot, StartOrigin origin) {
        try {
            Notification notification = buildNotification(boot);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NotificationIds.STATUS, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NotificationIds.STATUS, notification);
            }
            if (builder != null) {
                builder.onForegroundStarted();
            }
            if (!foreground) {
                foreground = true;
                boot.logger().event(EventCode.FGS_START, origin.name());
            }
            return true;
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException / ForegroundServiceTypeException (34+) / SecurityException:
            // retried by the alarm armed in refused() (F4, Q-66).
            boot.logger().event(EventCode.FGS_START_DENIED, origin.name());
            return false;
        }
    }

    /**
     * The S-10 builder; any failure falls back to a bare notification with the resource icon (F4).
     * {@code ensureChannels()} runs before the builder takes {@code channelId(MONITORING)}, so S-10 is normally posted
     * on {@code monitoring_v1} (DCR-06 / BUG-16): the CH-02 re-creation already ran at app start, which an upgrade
     * always goes through (the old process, and with it the old S-10, is gone). The F4 fallback calls
     * {@code ensureChannels()} once more (it may have failed above, or raced a concurrent readiness
     * {@code ensureChannelsGuarded()}) and then uses {@code channelId(MONITORING)}, which returns the legacy
     * {@code monitoring} id only while {@code monitoring_v1} is still missing.
     */
    private Notification buildNotification(MonitorBootDependencies boot) {
        try {
            if (builder == null) {
                boot.channels().ensureChannels();
                builder = StatusNotifications.builder(this, boot.channels(), boot.clock());
            }
            return builder.build(runtime != null ? runtime.currentSnapshot() : null);
        } catch (RuntimeException e) {
            try {
                boot.channels().ensureChannels();
            } catch (RuntimeException ignored) {
                // channelId() below still returns an existing CH-02 id if there is one
            }
            return fallbackNotification(this, boot.channels().channelId(ChannelId.MONITORING));
        }
    }

    /**
     * Bare S-10 used when the builder fails (F4): resource icon, ongoing, silent and alert-once (CH-02 is DEFAULT
     * importance since DCR-06 — never audible), lock-screen PUBLIC (SR-24 — AOSP stores a channel's requested PUBLIC
     * as „no override”, so the notification itself must say PUBLIC; BUG-07).
     *
     * @param context   context
     * @param channelId current CH-02 id
     * @return the notification
     */
    static Notification fallbackNotification(Context context, String channelId) {
        return new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(app.lightscout.ui.core.R.drawable.ic_stat_lightscout)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void registerScreenReceiver() {
        screenReceiver = new ScreenStateReceiver(runtime, statusUpdater);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
}
