package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.drawable.Icon;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.consumable.ReminderStage;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.platform.ChannelId;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.usecases.StatusRepository;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;

/** CP-09 TST-07 notification checks; run on RD-2 API 33 over USB, without Nightscout writes. */
@RunWith(AndroidJUnit4.class)
public class MonitorNotificationDeviceTest {

    private final NotificationManager notifications =
            DeviceSupport.context().getSystemService(NotificationManager.class);

    @After
    public void cleanUp() {
        notifications.cancel(NotificationIds.SITE_REMINDER);
        notifications.cancel(NotificationIds.SENSOR_REMINDER);
        notifications.cancel("synthetic-pump", NotificationIds.PUMP_ALERT);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void s10UsesResourceIconFirstThenBitmapAndIsPublic() {
        TestMonitorApplication app = DeviceSupport.app();
        NotificationChannels channels = app.monitorBootDependencies().channels();
        channels.ensureChannels();
        StatusNotificationBuilder builder = StatusNotifications.builder(app, channels, app.clock);
        StatusSnapshot snapshot = snapshot(app.clock.now());

        Notification first = builder.build(snapshot);
        builder.onForegroundStarted();
        Notification later = builder.build(snapshot);

        assertEquals(Icon.TYPE_RESOURCE, first.getSmallIcon().getType());
        assertEquals(Icon.TYPE_BITMAP, later.getSmallIcon().getType());
        assertEquals(Notification.VISIBILITY_PUBLIC, later.visibility);
        assertEquals("142 mg/dL ↑", later.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Just now", later.extras.getCharSequence(Notification.EXTRA_TEXT).toString());   // DCR-37: age only
        assertNotNull(later.contentIntent);
    }

    @Test
    public void s10IsRepostedAfterDismissalOnTheNextSnapshotChange() throws Exception {
        TestMonitorApplication app = DeviceSupport.app();
        NotificationChannels channels = app.monitorBootDependencies().channels();
        channels.ensureChannels();
        FakeStatus status = new FakeStatus(snapshot(app.clock.now()));
        StatusNotificationUpdater updater = new StatusNotificationUpdater(app, status,
                StatusNotifications.builder(app, channels, app.clock), null, null);
        try {
            updater.start();
            assertTrue(DeviceSupport.waitUntil(() -> active(NotificationIds.STATUS) != null, 3_000));
            notifications.cancel(NotificationIds.STATUS);
            status.emit(snapshot(app.clock.now().plusSeconds(1)));
            assertTrue(DeviceSupport.waitUntil(() -> active(NotificationIds.STATUS) != null, 3_000));
        } finally {
            updater.close();
            notifications.cancel(NotificationIds.STATUS);
        }
    }

    @Test
    public void remindersAndPumpAlertsUsePrivateChannelsAndExpectedCopy() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS);
        TestMonitorApplication app = DeviceSupport.app();
        NotificationChannels channels = app.monitorBootDependencies().channels();
        channels.ensureChannels();

        MonitorNotifications.reminderScheduler(app, channels).post(new ReminderNotice(ConsumableKind.SITE,
                ReminderStage.DUE_TOMORROW, "synthetic-site", Duration.ofDays(2), 3));
        assertTrue(DeviceSupport.waitUntil(() -> active(NotificationIds.SITE_REMINDER) != null, 3_000));
        Notification site = active(NotificationIds.SITE_REMINDER).getNotification();
        assertEquals("Site change due tomorrow", site.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Site age 2 d 0 h · lifetime 3 days",
                site.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(Notification.VISIBILITY_PUBLIC, site.visibility);
        assertChannelNotPrivate(channel(channels, ChannelId.REMINDERS));
        MonitorNotifications.reminderScheduler(app, channels).post(new ReminderNotice(ConsumableKind.SENSOR,
                ReminderStage.DUE_NOW, "synthetic-sensor", Duration.ofDays(10), 10));
        assertTrue(DeviceSupport.waitUntil(() -> active(NotificationIds.SENSOR_REMINDER) != null, 3_000));
        Notification sensor = active(NotificationIds.SENSOR_REMINDER).getNotification();
        assertEquals("Sensor change due now", sensor.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Sensor age 10 d 0 h", sensor.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(Notification.VISIBILITY_PUBLIC, sensor.visibility);
        MonitorNotifications.reminderScheduler(app, channels).cancel(ConsumableKind.SITE);
        MonitorNotifications.reminderScheduler(app, channels).cancel(ConsumableKind.SENSOR);
        assertTrue(DeviceSupport.waitUntil(() -> active(NotificationIds.SITE_REMINDER) == null, 3_000));
        assertTrue(DeviceSupport.waitUntil(() -> active(NotificationIds.SENSOR_REMINDER) == null, 3_000));

        MonitorNotifications.pumpAlertNotifier(app, channels).post(new Entry("synthetic-pump", EntryOrigin.TREATMENTS,
                EntryType.PUMP_ALERT, app.clock.now(), null, null, null, null, null,
                "Blocked\nflow", null, null, null, false, null));
        assertTrue(DeviceSupport.waitUntil(() -> active("synthetic-pump", NotificationIds.PUMP_ALERT) != null,
                3_000));
        Notification pump = active("synthetic-pump", NotificationIds.PUMP_ALERT).getNotification();
        assertEquals("Pump alert", pump.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Blockedflow", pump.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(Notification.VISIBILITY_PUBLIC, pump.visibility);
        assertChannelNotPrivate(channel(channels, ChannelId.PUMP_ALERTS));
        assertTrue(!channel(channels, ChannelId.PUMP_ALERTS).canBypassDnd());
    }

    /** VR-23 manual fixture: holds synthetic S-10 / reminder / pump notifications so the PO can lock the phone. */
    @Test
    public void lockScreenFixtureHoldsSyntheticNotificationsForManualCheck() throws Exception {
        String hold = InstrumentationRegistry.getArguments().getString("lockScreenHoldSeconds");
        Assume.assumeTrue("manual fixture; pass -e lockScreenHoldSeconds <n>", hold != null);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS);
        TestMonitorApplication app = DeviceSupport.app();
        NotificationChannels channels = app.monitorBootDependencies().channels();
        channels.ensureChannels();
        FakeStatus status = new FakeStatus(snapshot(app.clock.now()));
        StatusNotificationUpdater updater = new StatusNotificationUpdater(app, status,
                StatusNotifications.builder(app, channels, app.clock), null, null);
        try {
            updater.start();
            MonitorNotifications.reminderScheduler(app, channels).post(new ReminderNotice(ConsumableKind.SITE,
                    ReminderStage.DUE_TOMORROW, "synthetic-site", Duration.ofDays(2), 3));
            MonitorNotifications.reminderScheduler(app, channels).post(new ReminderNotice(ConsumableKind.SENSOR,
                    ReminderStage.DUE_NOW, "synthetic-sensor", Duration.ofDays(10), 10));
            MonitorNotifications.pumpAlertNotifier(app, channels).post(new Entry("synthetic-pump",
                    EntryOrigin.TREATMENTS, EntryType.PUMP_ALERT, app.clock.now(), null, null, null, null, null,
                    "Blocked\nflow", null, null, null, false, null));
            Thread.sleep(Long.parseLong(hold) * 1_000);
        } finally {
            updater.close();
            notifications.cancel(NotificationIds.STATUS);
        }
    }

    private android.service.notification.StatusBarNotification active(int id) {
        for (android.service.notification.StatusBarNotification notification : notifications.getActiveNotifications()) {
            if (notification.getId() == id) {
                return notification;
            }
        }
        return null;
    }

    private android.service.notification.StatusBarNotification active(String tag, int id) {
        for (android.service.notification.StatusBarNotification notification : notifications.getActiveNotifications()) {
            if (id == notification.getId() && tag.equals(notification.getTag())) {
                return notification;
            }
        }
        return null;
    }

    /** Android stores a requested channel PUBLIC as "no override" (BUG-07); the notification itself says PUBLIC. */
    private static void assertChannelNotPrivate(NotificationChannel channel) {
        int visibility = channel.getLockscreenVisibility();
        assertTrue("channel visibility " + visibility, visibility == Notification.VISIBILITY_PUBLIC
                || visibility == NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE);
    }

    private static NotificationChannel channel(NotificationChannels channels, ChannelId id) {
        return DeviceSupport.context().getSystemService(NotificationManager.class)
                .getNotificationChannel(channels.channelId(id));
    }

    private static StatusSnapshot snapshot(Instant now) {
        Reading reading = new Reading("synthetic-reading", now, 142, Trend.SINGLE_UP, "synthetic");
        return new StatusSnapshot(reading, Duration.ZERO, now.plus(Duration.ofMinutes(5)), false,
                new DataLossStatus(DataLossKind.NONE, null, null), FetchOutcome.OK, null, now, now, true, false,
                true, true, true, true, 55, 70, 250, 20, null, null,
                consumable(ConsumableKind.SITE), consumable(ConsumableKind.SENSOR), 0, Banner.NONE, now);
    }

    private static ConsumableStatus consumable(ConsumableKind kind) {
        return new ConsumableStatus(kind, ConsumableState.NONE_FOUND, null, null, null, null);
    }

    private static final class FakeStatus implements StatusRepository {
        private final StatusListener[] listeners = new StatusListener[1];
        private StatusSnapshot current;

        FakeStatus(StatusSnapshot current) {
            this.current = current;
        }

        @Override public StatusSnapshot current() { return current; }
        @Override public void addListener(StatusListener listener) { listeners[0] = listener; }
        @Override public void removeListener(StatusListener listener) { listeners[0] = null; }
        @Override public void refresh() { }
        @Override public void retry() { }

        void emit(StatusSnapshot snapshot) {
            current = snapshot;
            if (listeners[0] != null) {
                listeners[0].onStatus(snapshot);
            }
        }
    }
}
