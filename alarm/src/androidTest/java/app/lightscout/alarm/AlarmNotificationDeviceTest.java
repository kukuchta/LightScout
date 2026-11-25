package app.lightscout.alarm;

import static app.lightscout.alarm.AlarmDeviceSupport.aliveAlarmActivities;
import static app.lightscout.alarm.AlarmDeviceSupport.alarmNotification;
import static app.lightscout.alarm.AlarmDeviceSupport.app;
import static app.lightscout.alarm.AlarmDeviceSupport.cancelAlarmNotification;
import static app.lightscout.alarm.AlarmDeviceSupport.context;
import static app.lightscout.alarm.AlarmDeviceSupport.finishAlarmActivities;
import static app.lightscout.alarm.AlarmDeviceSupport.glucose;
import static app.lightscout.alarm.AlarmDeviceSupport.grantNotifications;
import static app.lightscout.alarm.AlarmDeviceSupport.interactive;
import static app.lightscout.alarm.AlarmDeviceSupport.noData;
import static app.lightscout.alarm.AlarmDeviceSupport.notRunning;
import static app.lightscout.alarm.AlarmDeviceSupport.onlyAlertOnce;
import static app.lightscout.alarm.AlarmDeviceSupport.resetAlarmState;
import static app.lightscout.alarm.AlarmDeviceSupport.ring;
import static app.lightscout.alarm.AlarmDeviceSupport.runtime;
import static app.lightscout.alarm.AlarmDeviceSupport.sleepScreen;
import static app.lightscout.alarm.AlarmDeviceSupport.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.Spanned;
import android.text.style.TtsSpan;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.model.Trend;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.channels.AndroidChannelBackend;
import app.lightscout.platform.channels.DefaultNotificationChannels;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * TST-07 CH-01 notification on a device (CP-07 §9): swiped notification re-posted (F9; the API 34 / 36 swipe gesture
 * itself is in VR-17 / VR-13 on RD-1 — here the delete intent the swipe fires is sent), notification Snooze action
 * (A-S11-1), SCREEN_OFF re-post (F11), S-11 kinds copy with the trend spoken (M3-34), category ALARM, PUBLIC, silent
 * posting on the current CH-01 id, immutable PendingIntents (TST-13). Target: RD-2 (API 33) over USB (CR-04).
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AlarmNotificationDeviceTest {

    /** SCREEN_OFF can arrive late on HyperOS (EV-07): bound for the screen to go off and for the delivery. */
    private static final long SCREEN_OFF_WAIT_MS = 15_000;

    /**
     * BUG-13: bound for the F11 re-post once SCREEN_OFF has reached the process (WF-02 step 2 budget 1 s, plus the
     * main-looper hop and the asynchronous cancel + post in the notification service).
     */
    private static final long REPOST_WAIT_MS = 5_000;

    /** BUG-13: attempts when S-09 was resumed at SCREEN_OFF, so the F11 precondition did not hold. */
    private static final int SCREEN_OFF_ATTEMPTS = 3;

    /** BUG-13: logcat tag and instrumentation status key of inconclusive F11 attempts (what the operator greps). */
    private static final String BUG_TAG = "BUG-13";

    /** Instrumentation status code "in progress" (ignored by test-result parsers). */
    private static final int STATUS_IN_PROGRESS = 2;

    /** Time HyperOS may take to launch S-09 from the full-screen intent of a fresh post (EV-08). */
    private static final long FSI_LAUNCH_SETTLE_MS = 2_000;

    @Before
    public void setUp() {
        grantNotifications();
        resetAlarmState(true);   // BUG-11: no S-09, notification, ringing state or snooze left by an earlier test
    }

    @After
    public void tearDown() {
        resetAlarmState(false);
    }

    @Test
    public void alarmNotification_categoryAlarm_public_ongoing_fsi_silent_onCurrentChannel() {
        ring(glucose(AlarmType.URGENT_LOW, 52, Trend.SINGLE_DOWN));
        assertTrue(waitUntil(() -> alarmNotification() != null, 5_000));
        Notification n = alarmNotification().getNotification();
        assertEquals(Notification.CATEGORY_ALARM, n.category);
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility);
        assertTrue((n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertNotNull(n.fullScreenIntent);
        assertNotNull(n.deleteIntent);
        assertNull(n.sound);
        assertNull(n.vibrate);
        assertEquals(1, n.actions.length);
        String current = new DefaultNotificationChannels(new AndroidChannelBackend(context()), null)
                .channelId(ChannelId.ALARMS);
        assertEquals(current, n.getChannelId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(n.contentIntent.isImmutable());
            assertTrue(n.fullScreenIntent.isImmutable());
            assertTrue(n.deleteIntent.isImmutable());
            assertTrue(n.actions[0].actionIntent.isImmutable());
        }
    }

    /**
     * VR-23 manual fixture (not a regression test): locks the phone, rings a synthetic URGENT_LOW alarm and holds it
     * for {@code -e lockScreenHoldSeconds <n>} so the PO can read the CH-01 notification / S-09 on the lock screen.
     * Skipped without that argument.
     */
    @Test
    public void lockScreenFixtureHoldsSyntheticAlarmForManualCheck() throws Exception {
        String arg = InstrumentationRegistry.getArguments().getString("lockScreenHoldSeconds");
        org.junit.Assume.assumeTrue("manual fixture: pass -e lockScreenHoldSeconds <n>", arg != null);
        long holdMs = Long.parseLong(arg) * 1_000;
        sleepScreen();
        Thread.sleep(5_000);
        ring(glucose(AlarmType.URGENT_LOW, 52, Trend.SINGLE_DOWN));
        assertTrue(waitUntil(() -> alarmNotification() != null, 5_000));
        Thread.sleep(holdMs);
    }

    @Test
    public void swipedNotification_repostedImmediately_onlyAlertOnce_notASnooze() throws Exception {
        ring(glucose(AlarmType.LOW, 66, Trend.FORTY_FIVE_DOWN));
        assertTrue(waitUntil(() -> alarmNotification() != null, 5_000));
        PendingIntent delete = alarmNotification().getNotification().deleteIntent;
        // What the swipe does. BUG-11: cancel is asynchronous in the notification service; without waiting, the
        // „re-posted” check below could see the old record and the next read find nothing (EV-08 NPE).
        assertTrue("swiped away", cancelAlarmNotification());
        delete.send();
        assertTrue("re-posted", waitUntil(() -> alarmNotification() != null, 3_000));
        StatusBarNotification reposted = alarmNotification();
        assertNotNull("still posted after the re-post", reposted);
        assertTrue(onlyAlertOnce(reposted));
        assertTrue("not a snooze", app().snoozes.isEmpty());
        assertNotNull(runtime().screen().current());
    }

    @Test
    public void notificationSnoozeAction_snoozes_cancels_closesS09() throws Exception {
        ring(glucose(AlarmType.LOW, 66, Trend.FORTY_FIVE_DOWN));
        assertTrue(waitUntil(() -> alarmNotification() != null, 5_000));
        alarmNotification().getNotification().actions[0].actionIntent.send();
        assertTrue(waitUntil(() -> app().snoozes.contains(AlarmType.LOW), 5_000));
        assertTrue("cancelled", waitUntil(() -> alarmNotification() == null, 5_000));
        assertNull(runtime().screen().current());
    }

    /**
     * F11 (TST-07 SCREEN_OFF re-post). BUG-13: the old single-shot version failed intermittently on RD-1 with no
     * way to tell why. Each attempt now observes, in the same process and through the same broadcast, whether
     * {@code SCREEN_OFF} reached the process at all ({@link ScreenOffProbe}) and the S-09 lifecycle timeline
     * ({@link S09Watch}). F11's only gate is {@code !screen.foreground()}, i.e. "S-09 is not resumed". The verdict per
     * attempt:
     * <ul>
     *   <li>fresh CH-01 re-post seen → pass (with the FSI, alerting);</li>
     *   <li>{@code SCREEN_OFF} not delivered within {@link #SCREEN_OFF_WAIT_MS} → fail at once (platform delivery,
     *       evidence for an Architect ruling — never retried, never skipped);</li>
     *   <li>delivered while the timeline shows S-09 <b>resumed</b> (a RESUMED event before the delivery with no
     *       PAUSED event after it) and no re-post → F11 rightly withheld the re-post (S-09 is shown anyway).
     *       Inconclusive: logged ({@code Log.w} and an instrumentation status line, tag {@code BUG-13}) and repeated
     *       from a clean state, at most {@link #SCREEN_OFF_ATTEMPTS} times; fails if never conclusive;</li>
     *   <li>delivered while S-09 was not resumed (whether or not an instance was alive or had been launched) and no
     *       re-post within {@link #REPOST_WAIT_MS} → fail at once (F11 product defect);</li>
     *   <li>foreground flag set at delivery although no S-09 instance exists or was launched → fail at once (stale
     *       flag, product).</li>
     * </ul>
     */
    @Test
    public void screenOff_whileS09NotInForeground_repostsWithFsi() {
        List<String> inconclusive = new ArrayList<>();
        for (int attempt = 1; attempt <= SCREEN_OFF_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1) {
                    resetAlarmState(true);
                }
                if (screenOffAttempt(attempt, inconclusive)) {
                    return;
                }
            } catch (AssertionError e) {
                if (inconclusive.isEmpty()) {
                    throw e;
                }
                // F6: a failure after inconclusive attempts keeps their evidence
                AssertionError wrapped = new AssertionError(e.getMessage() + " — earlier inconclusive attempts: "
                        + inconclusive);
                wrapped.initCause(e);
                throw wrapped;
            }
        }
        fail("F11 scenario inconclusive in all " + SCREEN_OFF_ATTEMPTS + " attempts (S-09 resumed at SCREEN_OFF "
                + "each time, so the F11 precondition never held): " + inconclusive);
    }

    /**
     * One F11 attempt.
     *
     * @return {@code true} when the fresh re-post was seen; {@code false} when inconclusive (recorded and reported)
     */
    private boolean screenOffAttempt(int attempt, List<String> inconclusive) {
        ring(glucose(AlarmType.HIGH, 262, Trend.FORTY_FIVE_UP));
        assertTrue(waitUntil(() -> alarmNotification() != null, 5_000));
        // BUG-11: the fresh post's full-screen intent may launch S-09 (keyguard up, or HyperOS even when unlocked);
        // its onResume would set the foreground flag back to true after this test cleared it, and F11 rightly does
        // not re-post while S-09 is in the foreground. Let that launch land, close S-09, then clear the flag.
        SystemClock.sleep(FSI_LAUNCH_SETTLE_MS);
        assertTrue("no S-09 instance left", finishAlarmActivities(5_000));
        runtime().screen().setForeground(false);
        // Probe and watch share one sequence: both record on the main thread, so the order of a delivery and the
        // S-09 lifecycle events is exact. Everything since registration is reported (F3).
        AtomicLong seq = new AtomicLong();
        long watchedFrom = SystemClock.elapsedRealtime();
        S09Watch s09 = new S09Watch(seq);
        ScreenOffProbe probe = new ScreenOffProbe(seq);
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(s09);
        ContextCompat.registerReceiver(context(), probe, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            // re-checked right before the screen goes off (a late FSI launch would re-create S-09 and set the
            // flag); the reference post / build time is taken after it
            assertTrue("precondition: no S-09 and S-09 not in the foreground — " + s09.describe(watchedFrom),
                    aliveAlarmActivities().isEmpty() && !runtime().screen().foreground());
            StatusBarNotification first = alarmNotification();
            assertNotNull(first);
            long firstPost = first.getPostTime();
            long firstBuilt = first.getNotification().when;   // set per build by NotificationCompat.Builder
            // F11 re-posts a freshly built notification (cancel + post), so a new build timestamp identifies the
            // re-post even where the system keeps the record's post time.
            BooleanSupplier reposted = () -> {
                StatusBarNotification sbn = alarmNotification();
                return sbn != null && (sbn.getNotification().when > firstBuilt || sbn.getPostTime() > firstPost);
            };
            sleepScreen();
            assertTrue("screen went off", waitUntil(() -> !interactive(), SCREEN_OFF_WAIT_MS));
            // HyperOS may deliver SCREEN_OFF late (EV-07): wait for the delivery (or the re-post itself, if the
            // production receiver ran first), then give the re-post its own bounded time from the delivery.
            waitUntil(() -> probe.delivered() || reposted.getAsBoolean(), SCREEN_OFF_WAIT_MS);
            if (!reposted.getAsBoolean() && probe.delivered()) {
                waitUntil(reposted, REPOST_WAIT_MS);
            }
            if (reposted.getAsBoolean()) {
                // F11 re-posts fresh: with the full-screen intent and alerting (unlike the F9 only-alert-once
                // re-post).
                StatusBarNotification sbn = alarmNotification();
                assertNotNull(sbn);
                assertNotNull("F11 re-post carries the FSI", sbn.getNotification().fullScreenIntent);
                assertFalse("F11 re-post is not only-alert-once", onlyAlertOnce(sbn));
                return true;
            }
            boolean resumedAtDelivery = probe.delivered() && s09.resumedAt(probe.deliveredSeq());
            String detail = "attempt " + attempt + ": " + probe.describe(watchedFrom) + "; timeline: S-09 resumed at "
                    + "delivery=" + resumedAtDelivery + "; " + s09.describe(watchedFrom) + "; interactive now="
                    + interactive();
            assertTrue("BUG-13: SCREEN_OFF was not delivered to the process within " + SCREEN_OFF_WAIT_MS
                    + " ms of the screen going off (platform delivery, not F11 logic) — " + detail, probe.delivered());
            if (probe.foregroundAtDelivery() && probe.aliveS09AtDelivery() == 0 && !s09.launched()) {
                fail("BUG-13: S-09 foreground flag set without any S-09 instance at SCREEN_OFF (product: stale flag "
                        + "blocks F11) — " + detail);
            }
            if (!resumedAtDelivery) {
                fail("re-posted on SCREEN_OFF: F11 did not re-post within " + REPOST_WAIT_MS + " ms of SCREEN_OFF "
                        + "although S-09 was not resumed at the delivery (product) — " + detail);
            }
            reportInconclusive(detail);
            inconclusive.add(detail);
            return false;
        } finally {
            context().unregisterReceiver(probe);
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(s09);
        }
    }

    /**
     * F2: an inconclusive attempt leaves evidence even when a later attempt passes — in logcat ({@code Log.w}, tag
     * {@code BUG-13}) and as an {@code INSTRUMENTATION_STATUS: BUG-13=…} line in {@code am instrument -r} output
     * (status code 2 = in progress, ignored by result parsers).
     */
    private static void reportInconclusive(String detail) {
        String line = "F11 attempt inconclusive (S-09 resumed at SCREEN_OFF): " + detail;
        Log.w(BUG_TAG, line);
        Bundle status = new Bundle();
        status.putString(BUG_TAG, line);
        InstrumentationRegistry.getInstrumentation().sendStatus(STATUS_IN_PROGRESS, status);
    }

    /**
     * BUG-13: a second {@code SCREEN_OFF} receiver in the test process, registered like the production one
     * ({@code RECEIVER_NOT_EXPORTED}, application context, main thread). It tells whether the broadcast reached the
     * process at all, when (a sequence number shared with {@link S09Watch}), and the S-09 state at that moment.
     *
     * <p>F4: the system delivers the broadcast to each registered receiver separately; the probe may run before or
     * after production's {@code AlarmScreenOffReceiver}, so its {@code foreground} value may differ from what F11 saw
     * if an S-09 lifecycle event ran in between. The verdict therefore prefers the {@link S09Watch} timeline; the
     * flag value is reported as support.
     */
    private static final class ScreenOffProbe extends BroadcastReceiver {
        private final AtomicLong seq;
        private volatile long deliveredAt = -1;
        private volatile long deliveredSeq = -1;
        private volatile boolean foregroundAtDelivery;
        private volatile int aliveS09AtDelivery;

        ScreenOffProbe(AtomicLong seq) {
            this.seq = seq;
        }

        @Override
        public void onReceive(Context c, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || deliveredAt >= 0) {
                return;
            }
            foregroundAtDelivery = runtime().screen().foreground();
            int alive = 0;
            for (Stage stage : Stage.values()) {
                if (stage == Stage.DESTROYED) {
                    continue;
                }
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                    if (a instanceof AlarmActivity) {
                        alive++;
                    }
                }
            }
            aliveS09AtDelivery = alive;
            deliveredSeq = seq.incrementAndGet();
            deliveredAt = SystemClock.elapsedRealtime();
        }

        boolean delivered() {
            return deliveredAt >= 0;
        }

        long deliveredSeq() {
            return deliveredSeq;
        }

        boolean foregroundAtDelivery() {
            return foregroundAtDelivery;
        }

        int aliveS09AtDelivery() {
            return aliveS09AtDelivery;
        }

        String describe(long since) {
            if (deliveredAt < 0) {
                return "SCREEN_OFF not delivered";
            }
            return "SCREEN_OFF delivered at +" + (deliveredAt - since) + " ms #" + deliveredSeq + " (probe: S-09 "
                    + "foreground flag=" + foregroundAtDelivery + ", alive S-09=" + aliveS09AtDelivery + ")";
        }
    }

    /** BUG-13: the S-09 lifecycle timeline (instance, stage, time, sequence) since the callback was registered. */
    private static final class S09Watch implements ActivityLifecycleCallback {
        /** One lifecycle event. */
        private record Event(int instance, Stage stage, long at, long seq) {
        }

        private final AtomicLong seq;
        private final List<Event> events = new CopyOnWriteArrayList<>();

        S09Watch(AtomicLong seq) {
            this.seq = seq;
        }

        @Override
        public void onActivityLifecycleChanged(Activity activity, Stage stage) {
            if (activity instanceof AlarmActivity) {
                events.add(new Event(System.identityHashCode(activity), stage, SystemClock.elapsedRealtime(),
                        seq.incrementAndGet()));
            }
        }

        /**
         * F1: some S-09 instance was resumed at sequence {@code at} — its last event before {@code at} is RESUMED
         * (no PAUSED, STOPPED or DESTROYED after it).
         */
        boolean resumedAt(long at) {
            Map<Integer, Stage> last = new HashMap<>();
            for (Event e : events) {
                if (e.seq() < at) {
                    last.put(e.instance(), e.stage());
                }
            }
            return last.containsValue(Stage.RESUMED);
        }

        /** An S-09 instance was created since registration. */
        boolean launched() {
            for (Event e : events) {
                if (e.stage() == Stage.PRE_ON_CREATE) {
                    return true;
                }
            }
            return false;
        }

        String describe(long since) {
            StringBuilder sb = new StringBuilder("S-09 events since watch start [");
            for (Event e : events) {
                sb.append(Integer.toHexString(e.instance())).append(':').append(e.stage()).append("@+")
                        .append(e.at() - since).append(" ms #").append(e.seq()).append(' ');
            }
            return sb.append(']').toString();
        }
    }

    @Test
    public void s11Kinds_copyMatchesCatalogue_trendSpoken() {
        AlarmTextRenderer r = new AlarmTextRenderer(context());
        // One instant for every view and every rendered text: two clock reads can straddle a minute boundary
        // (22:59.9 → „22 min”; production floors the age as intended).
        java.time.Instant now = app().clock.now();
        ZoneId utc = ZoneId.of("UTC");
        CharSequence urgent = r.notificationTitle(glucose(AlarmType.URGENT_LOW, 52, Trend.SINGLE_DOWN, now), now);
        assertEquals("Urgent low · 52 mg/dL ↓", urgent.toString());
        TtsSpan[] spans = ((Spanned) urgent).getSpans(0, urgent.length(), TtsSpan.class);
        assertEquals(1, spans.length);
        assertEquals("falling", spans[0].getArgs().getString(TtsSpan.ARG_TEXT));
        assertEquals("Low · 66 mg/dL ↘", r.notificationTitle(glucose(AlarmType.LOW, 66,
                Trend.FORTY_FIVE_DOWN, now), now).toString());
        assertEquals("High · 262 mg/dL ↗", r.notificationTitle(glucose(AlarmType.HIGH, 262,
                Trend.FORTY_FIVE_UP, now), now).toString());
        assertEquals("Snooze or open LightScout", r.notificationText(glucose(AlarmType.HIGH, 262,
                Trend.FORTY_FIVE_UP, now), utc));
        assertEquals("No data · 23 min", r.notificationTitle(noData(DataLossCause.UPLOADER, now), now).toString());
        assertEquals("No internet connection on this phone",
                r.notificationText(noData(DataLossCause.NO_INTERNET, now), utc));
        assertEquals("Nightscout is not responding",
                r.notificationText(noData(DataLossCause.NS_NOT_RESPONDING, now), utc));
        assertEquals("Nightscout works, but nothing new arrived from the uploader",
                r.notificationText(noData(DataLossCause.UPLOADER, now), utc));
        AlarmView watchdog = notRunning(now);
        assertEquals("LightScout is not running", r.notificationTitle(watchdog, now).toString());
        assertTrue(r.notificationText(watchdog, utc).startsWith("Last check "));
        assertTrue(r.notificationText(watchdog, utc).endsWith(". Open the app to restart monitoring."));
        assertEquals("Snooze 30 min", r.snoozeAction(watchdog));
    }
}
