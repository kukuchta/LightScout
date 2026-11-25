package app.lightscout.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.NotificationListenerService.Ranking;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import app.lightscout.platform.channels.AndroidChannelBackend;
import app.lightscout.platform.channels.DefaultNotificationChannels;

/**
 * TST-07 channel creation, {@code ensureChannels()} idempotence, F7 re-creation (CP-05 §9) and the CH-02
 * re-creation of DCR-06 / BUG-16 ({@code monitoring} LOW → {@code monitoring_v1} DEFAULT, silent) on a real
 * {@code NotificationManager}.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04 replaces emulators 29/31/34/36).
 * The API 36 observation of F7 is VR-27 on RD-1 in the device session ({@code docs/implementation/vr/VR-27.md}).
 * PERM-10 is granted / revoked for the test package with {@code cmd notification allow_dnd|disallow_dnd}.
 */
@RunWith(AndroidJUnit4.class)
public class NotificationChannelsDeviceTest {

    private Context context;
    private NotificationManager manager;
    private DefaultNotificationChannels channels;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        manager = context.getSystemService(NotificationManager.class);
        deleteAllChannels();
        DeviceShell.run("cmd notification disallow_dnd " + context.getPackageName());
        channels = new DefaultNotificationChannels(new AndroidChannelBackend(context), new RecordingLogger());
    }

    @After
    public void tearDown() {
        DeviceShell.run("cmd notification disallow_dnd " + context.getPackageName());
        deleteAllChannels();
    }

    @Test
    public void ensureChannelsCreatesTheFourChannelsPerSpec() {
        channels.ensureChannels();

        NotificationChannel alarms = manager.getNotificationChannel("alarms");
        assertNotNull(alarms);
        assertEquals(context.getString(R.string.s11_channel_alarms), alarms.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alarms.getImportance());
        assertPublicOrNoOverride("CH-01", alarms);
        assertNull("CH-01 silent", alarms.getSound());
        assertFalse(alarms.shouldVibrate());

        assertNull("fresh install: no legacy CH-02", manager.getNotificationChannel("monitoring"));
        assertMonitoringV1(manager.getNotificationChannel("monitoring_v1"));

        NotificationChannel reminders = manager.getNotificationChannel("reminders");
        assertEquals(context.getString(R.string.s11_channel_reminders), reminders.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, reminders.getImportance());
        assertFalse(reminders.canBypassDnd());
        assertPublicOrNoOverride("CH-03", reminders);
        assertNotNull("default sound", reminders.getSound());

        NotificationChannel pump = manager.getNotificationChannel("pump_alerts");
        assertEquals(context.getString(R.string.s11_channel_pump_alerts), pump.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, pump.getImportance());
        assertFalse(pump.canBypassDnd());
        assertPublicOrNoOverride("CH-04", pump);
        assertNotNull("default sound", pump.getSound());
    }

    @Test
    public void ensureChannelsTwiceKeepsOneChannelPerCh() {
        channels.ensureChannels();
        channels.ensureChannels();
        List<NotificationChannel> all = manager.getNotificationChannels();
        assertEquals(4, all.size());
    }

    @Test
    public void dcr06RecreatesLowMonitoringChannelOfAnOlderInstall() {
        // the CH-02 channel as created up to BUG-16
        NotificationChannel legacy = new NotificationChannel("monitoring",
                context.getString(R.string.s10_channel_monitoring), NotificationManager.IMPORTANCE_LOW);
        legacy.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        legacy.setSound(null, null);
        legacy.enableVibration(false);
        manager.createNotificationChannel(legacy);
        assertEquals("monitoring", channels.channelId(ChannelId.MONITORING));

        channels.ensureChannels();

        assertNull("old LOW channel deleted", manager.getNotificationChannel("monitoring"));
        assertMonitoringV1(manager.getNotificationChannel("monitoring_v1"));
        assertEquals("monitoring_v1", channels.channelId(ChannelId.MONITORING));
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channels.state(ChannelId.MONITORING).importance());
        assertEquals(4, manager.getNotificationChannels().size());

        channels.ensureChannels();                                   // idempotent
        assertEquals(4, manager.getNotificationChannels().size());
        assertNull(manager.getNotificationChannel("monitoring"));
    }

    @Test
    public void f7RecreatesAlarmChannelWithBypassAfterGrant() {
        channels.ensureChannels();                                   // before the grant
        assertFalse(manager.getNotificationChannel("alarms").canBypassDnd());

        DeviceShell.run("cmd notification allow_dnd " + context.getPackageName());
        assertTrue(manager.isNotificationPolicyAccessGranted());
        channels.ensureAlarmChannelBypass();

        assertNull(manager.getNotificationChannel("alarms"));
        NotificationChannel v1 = manager.getNotificationChannel("alarms_v1");
        assertNotNull(v1);
        assertTrue("VR-27: bypass honoured after re-creation", v1.canBypassDnd());
        assertEquals("alarms_v1", channels.channelId(ChannelId.ALARMS));
        assertTrue(channels.state(ChannelId.ALARMS).alarmReady());

        channels.ensureAlarmChannelBypass();                         // bypass already on → nothing happens
        assertEquals("alarms_v1", channels.channelId(ChannelId.ALARMS));
        assertEquals(4, manager.getNotificationChannels().size());
    }

    @Test
    public void f7DoesNothingWithoutPolicyAccess() {
        channels.ensureChannels();
        channels.ensureAlarmChannelBypass();
        assertNotNull(manager.getNotificationChannel("alarms"));
        assertEquals("alarms", channels.channelId(ChannelId.ALARMS));
    }

    /** DCR-06 / D-061: CH-02 is DEFAULT importance, never audible (no sound, no vibration), no bypass, PUBLIC. */
    private void assertMonitoringV1(NotificationChannel monitoring) {
        assertNotNull("CH-02 monitoring_v1", monitoring);
        assertEquals(context.getString(R.string.s10_channel_monitoring), monitoring.getName().toString());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, monitoring.getImportance());
        assertFalse(monitoring.canBypassDnd());
        assertPublicOrNoOverride("CH-02", monitoring);
        assertNull("CH-02 no sound", monitoring.getSound());
        assertFalse("CH-02 no vibration", monitoring.shouldVibrate());
    }

    /**
     * AOSP lets an app set only PRIVATE / SECRET as a channel's lock-screen visibility; a requested PUBLIC is
     * stored as {@code VISIBILITY_NO_OVERRIDE} (-1000; public as
     * {@link Ranking#VISIBILITY_NO_OVERRIDE}), so the notification's own
     * visibility (PUBLIC, set by production on every CH-01/CH-02 notification) applies. Both values mean
     * "content shown on the lock screen"; PRIVATE / SECRET would still fail.
     */
    private static void assertPublicOrNoOverride(String ch, NotificationChannel channel) {
        int visibility = channel.getLockscreenVisibility();
        assertTrue(ch + " lock-screen visibility PUBLIC or NO_OVERRIDE, was " + visibility,
                visibility == Notification.VISIBILITY_PUBLIC
                        || visibility == Ranking.VISIBILITY_NO_OVERRIDE);
    }

    private void deleteAllChannels() {
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            manager.deleteNotificationChannel(channel.getId());
        }
    }
}
