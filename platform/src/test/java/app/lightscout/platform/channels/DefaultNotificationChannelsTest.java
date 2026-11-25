package app.lightscout.platform.channels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;

import org.junit.Test;

import java.util.List;

import app.lightscout.platform.ChannelId;
import app.lightscout.platform.ChannelState;
import app.lightscout.platform.RecordingLogger;

/**
 * CMP-26 logic (CP-05 §8.1, §9): creation, idempotence, user changes kept, F7 versioned re-creation (R-74), state.
 */
public class DefaultNotificationChannelsTest {

    private final FakeChannelBackend backend = new FakeChannelBackend();
    private final RecordingLogger logger = new RecordingLogger();
    private final DefaultNotificationChannels channels = new DefaultNotificationChannels(backend, logger);

    @Test
    public void ensureChannelsCreatesTheFourChannels() {
        channels.ensureChannels();
        assertEquals(List.of("alarms", "monitoring_v1", "reminders", "pump_alerts"),
                List.copyOf(backend.channels.keySet()));
        assertEquals(ChannelSpec.of(ChannelId.ALARMS), backend.specs.get("alarms"));
        assertEquals(ChannelSpec.of(ChannelId.MONITORING), backend.specs.get("monitoring_v1"));
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, backend.channels.get("monitoring_v1").importance());
        assertEquals("fresh install: no delete", 4, backend.calls.size());
        assertEquals("monitoring_v1", channels.channelId(ChannelId.MONITORING));
        assertEquals(ChannelSpec.of(ChannelId.PUMP_ALERTS), backend.specs.get("pump_alerts"));
    }

    @Test
    public void ensureChannelsIsIdempotentAndKeepsUserChanges() {
        channels.ensureChannels();
        backend.userChange("reminders", NotificationManager.IMPORTANCE_NONE, false);
        backend.userChange("alarms", NotificationManager.IMPORTANCE_DEFAULT, false);
        backend.calls.clear();

        channels.ensureChannels();

        assertTrue(backend.calls.isEmpty());
        assertEquals(4, backend.channels.size());
        assertEquals(NotificationManager.IMPORTANCE_NONE, backend.channels.get("reminders").importance());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, backend.channels.get("alarms").importance());
    }

    @Test
    public void f7RecreatesAlarmChannelWhenPolicyGrantedAndBypassOff() {
        channels.ensureChannels();                       // created before the grant → bypass not honoured
        assertFalse(backend.channels.get("alarms").bypassDnd());
        backend.policyAccess = true;
        backend.calls.clear();

        channels.ensureAlarmChannelBypass();

        assertEquals(List.of("create alarms_v1", "delete alarms"), backend.calls);
        assertTrue(backend.channels.get("alarms_v1").bypassDnd());
        assertEquals("alarms_v1", channels.channelId(ChannelId.ALARMS));
        assertEquals(List.of("CHANNEL_RECREATED:1"), logger.events);
        assertEquals(4, backend.channels.size());
    }

    @Test
    public void f7DoesNothingWhenBypassAlreadyOn() {
        backend.policyAccess = true;
        channels.ensureChannels();                       // created after the grant → bypass honoured
        backend.calls.clear();

        channels.ensureAlarmChannelBypass();

        assertTrue(backend.calls.isEmpty());
        assertEquals("alarms", channels.channelId(ChannelId.ALARMS));
        assertTrue(logger.events.isEmpty());
    }

    @Test
    public void f7DoesNothingWithoutPolicyAccess() {
        channels.ensureChannels();
        backend.calls.clear();

        channels.ensureAlarmChannelBypass();

        assertTrue(backend.calls.isEmpty());
        assertTrue(logger.events.isEmpty());
    }

    @Test
    public void f7NeverRecreatesV1EvenWithoutBypass() {
        backend.channels.put("alarms_v1", new ExistingChannel("alarms_v1", NotificationManager.IMPORTANCE_HIGH, false));
        backend.policyAccess = true;

        channels.ensureAlarmChannelBypass();

        assertTrue(backend.calls.stream().noneMatch(c -> c.contains("alarms")));
        assertTrue(backend.channels.containsKey("alarms_v1"));
        assertTrue(logger.events.isEmpty());
        assertFalse("item 7 needs attention (VR-27 fallback)", channels.state(ChannelId.ALARMS).alarmReady());
    }

    @Test
    public void f7NeverRecreatesHigherVersions() {
        backend.channels.put("alarms_v4", new ExistingChannel("alarms_v4", NotificationManager.IMPORTANCE_HIGH, false));
        backend.policyAccess = true;

        channels.ensureAlarmChannelBypass();

        assertEquals(List.of("alarms_v4"), backend.channels.keySet().stream().filter(k -> k.startsWith("alarms"))
                .toList());
        assertTrue(logger.events.isEmpty());
    }

    @Test
    public void f7RecreatesAtMostOnceWhenUserTurnsBypassOffAfterwards() {
        channels.ensureChannels();
        backend.policyAccess = true;
        channels.ensureAlarmChannelBypass();                         // v0 → v1, once
        backend.userChange("alarms_v1", NotificationManager.IMPORTANCE_HIGH, false);   // user turns bypass off
        backend.calls.clear();

        for (int i = 0; i < 5; i++) {
            channels.ensureAlarmChannelBypass();
        }

        assertTrue(backend.calls.isEmpty());
        assertEquals(List.of("CHANNEL_RECREATED:1"), logger.events);
        assertEquals("alarms_v1", channels.channelId(ChannelId.ALARMS));
    }

    @Test
    public void f7RepeatedCallsAfterRecreationAreStable() {
        channels.ensureChannels();
        backend.policyAccess = true;
        for (int i = 0; i < 5; i++) {
            channels.ensureAlarmChannelBypass();
        }
        assertEquals(List.of("CHANNEL_RECREATED:1"), logger.events);
        assertEquals(4, backend.channels.size());
        assertTrue(backend.channels.get("alarms_v1").bypassDnd());
    }

    @Test
    public void ensureChannelsRemovesAdjacentLeftoverV0() {
        backend.channels.put("alarms", new ExistingChannel("alarms", NotificationManager.IMPORTANCE_HIGH, false));
        backend.channels.put("alarms_v1", new ExistingChannel("alarms_v1", NotificationManager.IMPORTANCE_HIGH, true));

        channels.ensureChannels();

        assertFalse(backend.channels.containsKey("alarms"));
        assertTrue(backend.channels.containsKey("alarms_v1"));
        assertEquals("alarms_v1", channels.channelId(ChannelId.ALARMS));
    }

    @Test
    public void ensureChannelsRemovesLeftoverOlderAlarmChannels() {
        backend.channels.put("alarms", new ExistingChannel("alarms", NotificationManager.IMPORTANCE_HIGH, false));
        backend.channels.put("alarms_v2", new ExistingChannel("alarms_v2", NotificationManager.IMPORTANCE_HIGH, true));

        channels.ensureChannels();

        assertFalse(backend.channels.containsKey("alarms"));
        assertTrue(backend.channels.containsKey("alarms_v2"));
        assertEquals("alarms_v2", channels.channelId(ChannelId.ALARMS));
    }

    @Test
    public void stateReportsCurrentChannel() {
        backend.policyAccess = true;
        channels.ensureChannels();
        ChannelState alarms = channels.state(ChannelId.ALARMS);
        assertEquals(new ChannelState("alarms", true, true, NotificationManager.IMPORTANCE_HIGH, true), alarms);
        assertTrue(alarms.alarmReady());

        ChannelState monitoring = channels.state(ChannelId.MONITORING);
        assertEquals("monitoring_v1", monitoring.currentId());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, monitoring.importance());
    }

    @Test
    public void stateOfMissingChannel() {
        ChannelState missing = channels.state(ChannelId.ALARMS);
        assertEquals(new ChannelState("alarms", false, false, NotificationManager.IMPORTANCE_NONE, false), missing);
        assertFalse(missing.alarmReady());
    }

    @Test
    public void userLoweredImportanceOrBypassOffMakesAlarmChannelNotReady() {
        backend.policyAccess = true;
        channels.ensureChannels();
        backend.userChange("alarms", NotificationManager.IMPORTANCE_DEFAULT, true);
        assertFalse(channels.state(ChannelId.ALARMS).alarmReady());
        backend.userChange("alarms", NotificationManager.IMPORTANCE_HIGH, false);
        assertFalse(channels.state(ChannelId.ALARMS).alarmReady());
        backend.userChange("alarms", NotificationManager.IMPORTANCE_NONE, true);
        ChannelState blocked = channels.state(ChannelId.ALARMS);
        assertFalse(blocked.enabled());
        assertFalse(blocked.alarmReady());
    }

    @Test
    public void dcr06RecreatesLowMonitoringChannelOfAnOlderInstall() {
        backend.preExisting("alarms", NotificationManager.IMPORTANCE_HIGH);
        backend.preExisting("monitoring", NotificationManager.IMPORTANCE_LOW);
        backend.preExisting("reminders", NotificationManager.IMPORTANCE_DEFAULT);
        backend.preExisting("pump_alerts", NotificationManager.IMPORTANCE_DEFAULT);
        assertEquals("before ensureChannels the old channel is still the one to post on",
                "monitoring", channels.channelId(ChannelId.MONITORING));

        channels.ensureChannels();

        assertEquals("create first, then delete (as F7)", List.of("create monitoring_v1", "delete monitoring"),
                backend.calls);
        assertFalse(backend.channels.containsKey("monitoring"));
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, backend.channels.get("monitoring_v1").importance());
        assertEquals(ChannelSpec.of(ChannelId.MONITORING), backend.specs.get("monitoring_v1"));
        assertEquals("monitoring_v1", channels.channelId(ChannelId.MONITORING));
        assertEquals("monitoring_v1", channels.state(ChannelId.MONITORING).currentId());
        assertEquals("CH-01 untouched", "alarms", channels.channelId(ChannelId.ALARMS));
        assertTrue("no CHANNEL_RECREATED (Q-52 counts CH-01 only)", logger.events.isEmpty());

        backend.calls.clear();
        channels.ensureChannels();
        assertTrue("idempotent", backend.calls.isEmpty());
        assertEquals(4, backend.channels.size());
    }

    @Test
    public void dcr06KeepsMigrationAfterInterruptedCreateThenDelete() {
        backend.preExisting("monitoring", NotificationManager.IMPORTANCE_LOW);
        backend.preExisting("monitoring_v1", NotificationManager.IMPORTANCE_DEFAULT);   // died before the delete
        assertEquals("monitoring_v1", channels.channelId(ChannelId.MONITORING));

        channels.ensureChannels();

        assertTrue(backend.calls.contains("delete monitoring"));
        assertFalse(backend.calls.contains("create monitoring_v1"));
        assertFalse(backend.channels.containsKey("monitoring"));
    }

    @Test
    public void dcr06RetriesDeleteRefusedForAForegroundServiceChannel() {
        backend.preExisting("monitoring", NotificationManager.IMPORTANCE_LOW);
        backend.fgsChannels.add("monitoring");

        channels.ensureChannels();                       // SecurityException swallowed

        assertTrue(backend.channels.containsKey("monitoring"));
        assertTrue(backend.channels.containsKey("monitoring_v1"));
        assertEquals("posters move to the new channel anyway", "monitoring_v1",
                channels.channelId(ChannelId.MONITORING));

        backend.fgsChannels.clear();                     // S-10 re-posted on monitoring_v1
        channels.ensureChannels();
        assertFalse(backend.channels.containsKey("monitoring"));
        assertEquals(4, backend.channels.size());
    }

    @Test
    public void refusedAlarmLeftoverDeleteNeitherThrowsNorSkipsTheMonitoringMigration() {
        backend.preExisting("alarms", NotificationManager.IMPORTANCE_HIGH);
        backend.preExisting("alarms_v1", NotificationManager.IMPORTANCE_HIGH);
        backend.preExisting("monitoring", NotificationManager.IMPORTANCE_LOW);
        backend.fgsChannels.add("alarms");               // delete throws SecurityException

        channels.ensureChannels();                       // must not throw

        assertTrue(backend.calls.contains("delete alarms"));
        assertTrue("CH-02 migration still ran", backend.channels.containsKey("monitoring_v1"));
        assertFalse(backend.channels.containsKey("monitoring"));
        assertTrue(backend.channels.containsKey("reminders"));
        assertTrue(backend.channels.containsKey("pump_alerts"));
        assertEquals("alarms_v1", channels.channelId(ChannelId.ALARMS));

        backend.fgsChannels.clear();
        channels.ensureChannels();                       // retried
        assertFalse(backend.channels.containsKey("alarms"));
    }

    @Test
    public void alarmVersionParsing() {
        assertEquals(0, DefaultNotificationChannels.alarmVersion("alarms"));
        assertEquals(12, DefaultNotificationChannels.alarmVersion("alarms_v12"));
        assertEquals(-1, DefaultNotificationChannels.alarmVersion("monitoring"));
        assertEquals(-1, DefaultNotificationChannels.alarmVersion("alarms_vx"));
        assertEquals("alarms", DefaultNotificationChannels.alarmId(0));
        assertEquals("alarms_v3", DefaultNotificationChannels.alarmId(3));
    }
}
