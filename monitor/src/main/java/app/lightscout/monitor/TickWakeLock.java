package app.lightscout.monitor;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Per-owner timed partial wake locks (CP-00 §4, WF-01 step 1; 🏛️ Q-65 / Q-68, IR-73). Each owner has its own
 * non-reference-counted {@code PowerManager.WakeLock}, so one owner's release never frees another's:
 *
 * <ul>
 *   <li>{@link #onAlarmReceipt(Context)} — owner „alarm”: one timed 30 s acquisition when an alarm receiver fires,
 *       bridging the service start; it simply times out.</li>
 *   <li>{@link #forTick(Context)} — owner „tick”: a {@link WakeLockSession} held by {@link TickRunner}, refreshed by
 *       a daemon timer thread (not {@code monitorExecutor}).</li>
 * </ul>
 */
final class TickWakeLock {

    private static final String TAG_TICK = "LightScout:tick";
    private static final String TAG_ALARM = "LightScout:alarm";
    private static final ScheduledExecutorService REFRESHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LightScout-wakelock");
        t.setDaemon(true);
        return t;
    });
    private static PowerManager.WakeLock alarmLock;

    private TickWakeLock() {
    }

    /**
     * Owner „alarm”: a single timed 30 s acquisition on alarm receipt (released by its timeout).
     *
     * @param context any context
     */
    static void onAlarmReceipt(Context context) {
        synchronized (TickWakeLock.class) {
            if (alarmLock == null) {
                alarmLock = newLock(context, TAG_ALARM);
            }
            if (alarmLock != null) {
                alarmLock.acquire(WakeLockBudget.ACQUIRE_MAX_MS);
            }
        }
    }

    /**
     * Owner „tick”: the session used by {@link TickRunner}.
     *
     * @param context any context
     * @return the session
     */
    static WakeLockSession forTick(Context context) {
        PowerManager.WakeLock wl = newLock(context, TAG_TICK);
        WakeLockSession.Handle handle = new WakeLockSession.Handle() {
            @Override
            public void acquire(long timeoutMs) {
                if (wl != null) {
                    wl.acquire(timeoutMs);
                }
            }

            @Override
            public void release() {
                if (wl != null && wl.isHeld()) {
                    wl.release();
                }
            }

            @Override
            public boolean isHeld() {
                return wl != null && wl.isHeld();
            }
        };
        WakeLockSession.Timer timer = (task, periodMs) -> {
            ScheduledFuture<?> f = REFRESHER.scheduleWithFixedDelay(task, periodMs, periodMs, TimeUnit.MILLISECONDS);
            return () -> f.cancel(false);
        };
        return new WakeLockSession(handle, timer, SystemClock::elapsedRealtime);
    }

    private static PowerManager.WakeLock newLock(Context context, String tag) {
        PowerManager pm = context.getApplicationContext().getSystemService(PowerManager.class);
        if (pm == null) {
            return null;
        }
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        wl.setReferenceCounted(false);
        return wl;
    }
}
