package app.lightscout.platform.channels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;

import org.junit.Test;

import app.lightscout.platform.ChannelId;
import app.lightscout.platform.R;

/**
 * CP-05 §8.1 channel table (TST-07 logic part): ids, name resources, importance, DND bypass, lock-screen visibility,
 * silence.
 */
public class ChannelSpecTest {

    @Test
    public void alarmsChannel() {
        ChannelSpec spec = ChannelSpec.of(ChannelId.ALARMS);
        assertEquals("alarms", spec.baseId());
        assertEquals(R.string.s11_channel_alarms, spec.nameRes());
        assertEquals(NotificationManager.IMPORTANCE_HIGH, spec.importance());
        assertTrue(spec.bypassDnd());
        assertEquals(Notification.VISIBILITY_PUBLIC, spec.lockscreenVisibility());
        assertTrue("CH-01 has no channel sound (AlarmPlayer)", spec.silent());
    }

    @Test
    public void monitoringChannel() {
        ChannelSpec spec = ChannelSpec.of(ChannelId.MONITORING);
        assertEquals("DCR-06: re-created under a new id", "monitoring_v1", spec.baseId());
        assertEquals(ChannelSpec.MONITORING_ID, spec.baseId());
        assertEquals("monitoring", ChannelSpec.MONITORING_LEGACY_ID);
        assertEquals(R.string.s10_channel_monitoring, spec.nameRes());
        assertEquals("DCR-06 / D-061: DEFAULT (shown on the HyperOS lock screen)",
                NotificationManager.IMPORTANCE_DEFAULT, spec.importance());
        assertFalse(spec.bypassDnd());
        assertEquals(Notification.VISIBILITY_PUBLIC, spec.lockscreenVisibility());
        assertTrue("DCR-06: never audible — no channel sound, no vibration", spec.silent());
    }

    @Test
    public void remindersChannel() {
        ChannelSpec spec = ChannelSpec.of(ChannelId.REMINDERS);
        assertEquals("reminders", spec.baseId());
        assertEquals(R.string.s11_channel_reminders, spec.nameRes());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, spec.importance());
        assertFalse(spec.bypassDnd());
        assertEquals(Notification.VISIBILITY_PUBLIC, spec.lockscreenVisibility());
        assertFalse("default sound", spec.silent());
    }

    @Test
    public void pumpAlertsChannel() {
        ChannelSpec spec = ChannelSpec.of(ChannelId.PUMP_ALERTS);
        assertEquals("pump_alerts", spec.baseId());
        assertEquals(R.string.s11_channel_pump_alerts, spec.nameRes());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, spec.importance());
        assertFalse(spec.bypassDnd());
        assertEquals(Notification.VISIBILITY_PUBLIC, spec.lockscreenVisibility());
        assertFalse("default sound", spec.silent());
    }

    @Test
    public void exactlyFourChannels() {
        assertEquals(4, ChannelId.values().length);
    }
}
