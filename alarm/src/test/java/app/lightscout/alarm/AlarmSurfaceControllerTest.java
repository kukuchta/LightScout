package app.lightscout.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * CH-01 posting rules: Q-52 current channel id per post, F9 swipe re-post (only-alert-once, not a snooze), F11
 * screen-off re-post only when S-09 is not in the foreground, F10 call-end re-post with the FSI, one alarm
 * notification at a time.
 */
public class AlarmSurfaceControllerTest {

    private Fakes f;
    private AlarmScreenModel screen;
    private AlarmSurfaceController surface;

    @Before
    public void setUp() {
        f = new Fakes();
        screen = new AlarmScreenModel();
        surface = new AlarmSurfaceController(f.notifications, f.channels, screen, f.screenOff);
    }

    @Test
    public void everyPost_looksUpTheCurrentAlarmChannelId() {
        surface.show(Fakes.view(AlarmType.LOW, 66));
        assertEquals("alarms", f.notifications.postedChannel);
        f.channels.alarmId = "alarms_v1";   // F7 re-created CH-01 (the old notification was cancelled by the system)
        surface.update(Fakes.view(AlarmType.LOW, 64));
        assertEquals("alarms_v1", f.notifications.postedChannel);
        f.channels.alarmId = "alarms_v2";
        surface.onNotificationDeleted(AlarmType.LOW);
        assertEquals("alarms_v2", f.notifications.postedChannel);
        assertEquals(3, f.channels.lookups);
    }

    @Test
    public void swipe_repostsImmediately_onlyAlertOnce() {
        surface.show(Fakes.view(AlarmType.LOW, 66));
        f.notifications.cancel();   // the user swiped it away (system removed it)
        surface.onNotificationDeleted(AlarmType.LOW);
        assertEquals(1, f.notifications.active());
        assertTrue(f.notifications.postedOnlyAlertOnce);
        assertEquals(AlarmType.LOW, screen.current().type());   // still ringing: a swipe is not a snooze
    }

    @Test
    public void swipe_ofAnotherType_orWhenNotRinging_isIgnored() {
        surface.onNotificationDeleted(AlarmType.LOW);
        assertEquals(0, f.notifications.active());
        surface.show(Fakes.view(AlarmType.URGENT_LOW, 50));
        f.notifications.log.clear();
        surface.onNotificationDeleted(AlarmType.LOW);
        assertTrue(f.notifications.log.isEmpty());
    }

    @Test
    public void screenOff_repostsWithFsi_onlyWhenS09NotInForeground() {
        surface.show(Fakes.view(AlarmType.HIGH, 262));
        f.notifications.log.clear();
        screen.setForeground(true);
        f.screenOff.fire();
        assertTrue(f.notifications.log.isEmpty());
        screen.setForeground(false);
        f.screenOff.fire();
        assertEquals(List.of("cancel", "post(alarms,HIGH,fsi)"), f.notifications.log);
    }

    @Test
    public void callEnded_repostsWithFsi() {
        surface.show(Fakes.view(AlarmType.URGENT_LOW, 50));
        f.notifications.log.clear();
        surface.onCallEnded();
        assertEquals(List.of("cancel", "post(alarms,URGENT_LOW,fsi)"), f.notifications.log);
    }

    @Test
    public void close_cancels_closesScreen_stopsWatchingScreenOff() {
        surface.show(Fakes.view(AlarmType.LOW, 66));
        surface.close();
        assertEquals(0, f.notifications.active());
        assertNull(screen.current());
        assertNull(f.screenOff.callback);
        f.notifications.log.clear();
        surface.onCallEnded();
        surface.onScreenOff();
        assertTrue(f.notifications.log.isEmpty());
    }

    @Test
    public void replace_cancelsOld_postsNewWithFsi_oneAtATime() {
        surface.show(Fakes.view(AlarmType.LOW, 66));
        f.notifications.log.clear();
        surface.replace(Fakes.view(AlarmType.URGENT_LOW, 52));
        assertEquals(List.of("cancel", "post(alarms,URGENT_LOW,fsi)"), f.notifications.log);
        assertEquals(1, f.notifications.active());
        assertEquals(1, f.screenOff.registers);
    }

    @Test
    public void screenModelListeners_seeEveryViewAndTheClose() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        screen.addListener(v -> seen.add(v == null ? "closed" : v.type().name()));
        surface.show(Fakes.view(AlarmType.LOW, 66));
        surface.replace(Fakes.view(AlarmType.URGENT_LOW, 52));
        surface.close();
        assertEquals(List.of("LOW", "URGENT_LOW", "closed"), seen);
    }
}
