package app.lightscout.monitor;

import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.Logger;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.channels.AndroidChannelBackend;
import app.lightscout.platform.channels.DefaultNotificationChannels;
import app.lightscout.platform.clock.AndroidClock;
import app.lightscout.platform.connectivity.AndroidConnectivity;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test stand-in for the CP-15 composition root: implements {@link MonitorRuntimeProvider} with fakes for IF-20,
 * IF-21 and the stores, and the real CMP-26 channels, CMP-28 clock and connectivity. Records whether
 * {@link MonitorService} was already foreground when the runtime was first requested (F4 „startForeground first”).
 */
public class TestMonitorApplication extends Application implements MonitorRuntimeProvider {

    final DeviceFakes.Flags flags = new DeviceFakes.Flags();
    final DeviceFakes.Log logger = new DeviceFakes.Log();
    final DeviceFakes.MonitorStore monitorStore = new DeviceFakes.MonitorStore();
    final DeviceFakes.AlarmStore alarmStore = new DeviceFakes.AlarmStore();
    final Clock clock = new AndroidClock();
    final DeviceFakes.Cycle cycle = new DeviceFakes.Cycle(monitorStore, clock);
    final ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService monitorExecutor = Executors.newSingleThreadExecutor();

    volatile boolean runtimeRequested;
    /**
     * {@code true} when {@link MonitorService} was already a foreground service (AMS record, set synchronously by
     * {@code startForeground}) at the first runtime request since the last {@link #resetRuntimeRecord()}. The S-10
     * notification itself is posted asynchronously, so {@code getActiveNotifications()} is not a reliable seam here.
     */
    volatile boolean foregroundBeforeRuntime;

    private NotificationChannels channels;
    private Connectivity connectivity;
    private MonitorRuntime runtime;

    @Override
    public synchronized MonitorBootDependencies monitorBootDependencies() {
        if (channels == null) {
            channels = new DefaultNotificationChannels(new AndroidChannelBackend(this), logger);
        }
        NotificationChannels ch = channels;
        return new MonitorBootDependencies() {
            @Override
            public app.lightscout.domain.port.AppFlagsStore flags() {
                return flags;
            }

            @Override
            public NotificationChannels channels() {
                return ch;
            }

            @Override
            public Logger logger() {
                return logger;
            }

            @Override
            public Clock clock() {
                return clock;
            }

            @Override
            public Executor receiverExecutor() {
                return receiverExecutor;
            }
        };
    }

    @Override
    public synchronized MonitorRuntime monitorRuntime() {
        if (!runtimeRequested) {
            runtimeRequested = true;
            foregroundBeforeRuntime = monitorServiceForeground();
        }
        if (runtime == null) {
            MonitorBootDependencies boot = monitorBootDependencies();
            connectivity = new AndroidConnectivity(this, logger);
            runtime = new MonitorRuntime(this, new MonitorRuntime.Collaborators(() -> cycle, DeviceFakes.Status::new,
                    monitorStore, alarmStore, new DeviceFakes.SettingsFake(), flags, boot.channels(), connectivity,
                    clock, logger, monitorExecutor));
        }
        return runtime;
    }

    /**
     * Returns the S-10 / paused notification if posted.
     *
     * @return the notification or {@code null}
     */
    StatusBarNotification statusNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        for (StatusBarNotification n : nm.getActiveNotifications()) {
            if (n.getId() == NotificationIds.STATUS) {
                return n;
            }
        }
        return null;
    }

    /**
     * Forgets the first runtime request so that the next one (e.g. from a freshly started {@link MonitorService}) is
     * recorded again; the application object outlives the individual tests.
     */
    synchronized void resetRuntimeRecord() {
        runtimeRequested = false;
        foregroundBeforeRuntime = false;
    }

    @SuppressWarnings("deprecation")   // getRunningServices still returns the caller's own services
    boolean monitorServiceForeground() {
        ActivityManager am = getSystemService(ActivityManager.class);
        String name = MonitorService.class.getName();
        for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
            if (getPackageName().equals(info.service.getPackageName())
                    && name.equals(info.service.getClassName())) {
                return info.foreground;
            }
        }
        return false;
    }
}
