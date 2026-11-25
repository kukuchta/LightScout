package app.lightscout.alarm;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.model.Trend;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Shell, ringing and notification helpers for the {@code :alarm} instrumented tests (target RD-2, API 33, over USB;
 * every run ends with {@code adb uninstall} of the app and test APKs — SR-34).
 */
final class AlarmDeviceSupport {

    private AlarmDeviceSupport() {
    }

    static TestAlarmApplication app() {
        return ApplicationProvider.getApplicationContext();
    }

    static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    static AlarmRuntime runtime() {
        return app().alarmRuntime();
    }

    /** Grants POST_NOTIFICATIONS (API 33) so that CH-01 posts are visible to the tests. */
    static void grantNotifications() {
        shell("pm grant " + context().getPackageName() + " android.permission.POST_NOTIFICATIONS");
    }

    static String shell(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            pfd.close();
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static AlarmView glucose(AlarmType type, int sgv, Trend trend) {
        return glucose(type, sgv, trend, app().clock.now());
    }

    /** Glucose alarm view relative to {@code now}; use one instant for a view and the text rendered from it. */
    static AlarmView glucose(AlarmType type, int sgv, Trend trend, Instant now) {
        return new AlarmView(type, sgv, trend, now.minusSeconds(120), null, null, null, 15);
    }

    static AlarmView noData(DataLossCause cause) {
        return noData(cause, app().clock.now());
    }

    /** No-data view (23 min) relative to {@code now}; use one instant for a view and the text rendered from it. */
    static AlarmView noData(DataLossCause cause, Instant now) {
        return new AlarmView(AlarmType.NO_DATA, null, Trend.UNKNOWN, now.minusSeconds(23 * 60), cause, null,
                now.minusSeconds(23 * 60), 30);
    }

    static AlarmView notRunning() {
        return notRunning(app().clock.now());
    }

    /** Not-running view relative to {@code now}; use one instant for a view and the text rendered from it. */
    static AlarmView notRunning(Instant now) {
        return new AlarmView(AlarmType.NOT_RUNNING, null, Trend.UNKNOWN, null, null, now.minusSeconds(600), null,
                30);
    }

    /** Applies commands on the monitor thread (as {@code SyncCycle} does) and waits for them. */
    static void apply(AlarmCommand... commands) {
        CountDownLatch done = new CountDownLatch(1);
        app().monitorExecutor.execute(() -> {
            try {
                runtime().apply(List.of(commands));
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void ring(AlarmView view) {
        apply(new AlarmCommand.Start(view.type(), view));
    }

    /** Stops whatever rings (test clean-up). */
    static void stopAll() {
        for (AlarmType t : AlarmType.values()) {
            apply(new AlarmCommand.Stop(t));
        }
        apply();
    }

    static boolean waitUntil(BooleanSupplier condition, long timeoutMs) {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (condition.getAsBoolean()) {
                return true;
            }
            SystemClock.sleep(100);
        }
        return condition.getAsBoolean();
    }

    /** Returns the posted CH-01 alarm notification (id 2) or {@code null}. */
    static StatusBarNotification alarmNotification() {
        NotificationManager nm = context().getSystemService(NotificationManager.class);
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (sbn.getId() == AndroidNotificationPort.NOTIFICATION_ID) {
                return sbn;
            }
        }
        return null;
    }

    static boolean onlyAlertOnce(StatusBarNotification sbn) {
        return (sbn.getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0;
    }

    /** Returns the resumed {@link AlarmActivity}, or {@code null}. */
    static AlarmActivity resumedAlarmActivity() {
        AlarmActivity[] out = new AlarmActivity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for (Activity a : resumed) {
                if (a instanceof AlarmActivity alarm) {
                    out[0] = alarm;
                }
            }
        });
        return out[0];
    }

    /**
     * Returns every {@link AlarmActivity} instance of this process that is not destroyed yet (any stage, including
     * finishing ones and instances stopped behind the keyguard).
     */
    static List<AlarmActivity> aliveAlarmActivities() {
        List<AlarmActivity> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ActivityLifecycleMonitor monitor = ActivityLifecycleMonitorRegistry.getInstance();
            for (Stage stage : Stage.values()) {
                if (stage == Stage.DESTROYED) {
                    continue;
                }
                for (Activity a : monitor.getActivitiesInStage(stage)) {
                    if (a instanceof AlarmActivity alarm && !out.contains(alarm)) {
                        out.add(alarm);
                    }
                }
            }
        });
        return out;
    }

    /**
     * Finishes every live {@link AlarmActivity} and waits until none is left. S-09 is {@code singleInstance}: a
     * survivor of an earlier test (e.g. launched late by a full-screen intent) would receive the next test's launch
     * through {@code onNewIntent} instead of a fresh instance being created (BUG-11).
     *
     * @param timeoutMs how long to wait for the destruction
     * @return {@code true} when no instance is left
     */
    static boolean finishAlarmActivities(long timeoutMs) {
        for (AlarmActivity a : aliveAlarmActivities()) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(a::finishAndRemoveTask);
        }
        return waitUntil(() -> aliveAlarmActivities().isEmpty(), timeoutMs);
    }

    /**
     * Cancels the CH-01 alarm notification and waits until the system no longer lists it ({@code cancel} is
     * asynchronous in the notification service).
     *
     * @return {@code true} when it is gone
     */
    static boolean cancelAlarmNotification() {
        context().getSystemService(NotificationManager.class).cancel(AndroidNotificationPort.NOTIFICATION_ID);
        return waitUntil(() -> alarmNotification() == null, 3_000);
    }

    /**
     * Wakes the screen and, when the keyguard is not secure, dismisses it ({@code input keyevent KEYCODE_WAKEUP} +
     * {@code wm dismiss-keyguard}). A secure keyguard (PIN / pattern, required by {@code fsiOnLockedDevice}) cannot be
     * unlocked from a test: {@code wm dismiss-keyguard} would only raise the bouncer, which takes the key focus away
     * from S-09 (volume-key tests). Tests that run after a locking test therefore must not depend on the keyguard
     * being down (S-09 shows over it; the bogus-extra test starts its activity directly, not through
     * {@code ActivityScenario}'s non-lock-screen bootstrap activity).
     *
     * @return {@code true} when the screen is on and the keyguard is down
     */
    static boolean wakeAndUnlock() {
        wakeScreen();
        KeyguardManager km = context().getSystemService(KeyguardManager.class);
        waitUntil(AlarmDeviceSupport::interactive, 3_000);
        if (km.isKeyguardLocked() && !km.isKeyguardSecure()) {
            shell("wm dismiss-keyguard");
            waitUntil(() -> !km.isKeyguardLocked(), 3_000);
        }
        return interactive() && !km.isKeyguardLocked();
    }

    /**
     * BUG-11: brings the process and the device to a known state before and after every alarm test, because the
     * runtime, S-09 ({@code singleInstance}), the alarm notification and the screen / keyguard are shared by all tests
     * of a class run: screen on (keyguard dismissed where possible), nothing ringing (every type stopped on the
     * monitor thread, F8 restore point applied, {@code SCREEN_OFF} receiver unregistered, the player's snooze
     * suppression cleared), no S-09 instance, no CH-01 notification, S-09 foreground flag false, no recorded snooze,
     * clock un-shifted.
     *
     * @param strict {@code true} in {@code @Before}: fail the test when no known state could be reached (an S-09
     *               instance or the CH-01 notification survives); {@code false} in {@code @After}: best effort, so a
     *               tear-down never hides the test's own failure
     */
    static void resetAlarmState(boolean strict) {
        wakeAndUnlock();
        stopAll();                       // queued IF-25 snooze Stops run first: one monitor thread, FIFO
        boolean noS09 = finishAlarmActivities(5_000);
        boolean noNotification = cancelAlarmNotification();
        runtime().screen().setForeground(false);
        apply();                         // drain the monitor thread once more
        app().snoozes.clear();
        app().clock.offset = java.time.Duration.ZERO;
        app().clock.zone = java.time.ZoneId.systemDefault();
        if (strict) {                    // asserted after every step ran, so the state is as clean as it can get
            org.junit.Assert.assertTrue("reset: no S-09", noS09);
            org.junit.Assert.assertTrue("reset: CH-01 gone", noNotification);
        }
    }

    static void sleepScreen() {
        shell("input keyevent KEYCODE_SLEEP");
    }

    static void wakeScreen() {
        shell("input keyevent KEYCODE_WAKEUP");
    }

    static void pressPower() {
        shell("input keyevent KEYCODE_POWER");
    }

    static boolean interactive() {
        return context().getSystemService(android.os.PowerManager.class).isInteractive();
    }
}
