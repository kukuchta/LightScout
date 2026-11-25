package app.lightscout.usecases.sync;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.usecases.SyncResult;
import app.lightscout.usecases.alarm.AlarmStateTransitions;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.page;
import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Fakes.treatment;
import static app.lightscout.usecases.fakes.Harness.NOW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** SS-05 review round 1: IR-36 … IR-42. */
public class ReviewRound1FixesTest {

    // ------------------------------------------------------------------ IR-36

    @Test
    public void ir36_snoozeWithUnknownState_neverWritesOverStore_mergedOnNextRead() {
        Harness h = new Harness();
        Instant highSnooze = NOW.plus(Duration.ofMinutes(40));
        h.alarmState.value = new AlarmState(null,
                Map.of(AlarmType.HIGH, new AlarmTypeState(null, highSnooze, 0, null)), 7);
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));    // Low holds
        h.alarmState.fail = true;                                  // IF-14 get and put fail since process start
        h.actions.snooze(AlarmType.LOW);
        h.monitorExecutor.runAll();
        assertTrue("nothing written over the store", h.alarmState.puts.isEmpty());
        assertEquals(new AlarmCommand.Stop(AlarmType.LOW), h.alarmController.applied.get(0).get(0));
        assertTrue("pending snooze keeps Low silent", h.alarmController.applied.get(1).isEmpty());

        h.alarmState.fail = false;                                 // store recovers
        h.clock.advanceMinutes(1);
        h.tick();
        AlarmState stored = h.alarmState.value;
        assertEquals(NOW.plus(Duration.ofMinutes(15)), stored.perType().get(AlarmType.LOW).snoozedUntil());
        assertNull("AL-5a: reading 60 is outside High, its stored snooze ends", stored.perType().get(AlarmType.HIGH));
        assertEquals(Integer.valueOf(7), stored.savedUserAlarmVolume());
        assertTrue(h.alarmController.last().isEmpty());
    }

    // ------------------------------------------------------------------ IR-37

    @Test
    public void ir37_readingRangeFails_futureDatedLowFromFetchedReadingsStillRings() {
        Harness h = new Harness();
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> page(true, List.of(reading("ok", NOW.minusSeconds(60), 120),
                reading("future", NOW.plus(Duration.ofMinutes(30)), 60)));
        h.readings.fail = true;
        h.tick();
        AlarmCommand.Start start = (AlarmCommand.Start) h.alarmController.last().get(0);
        assertEquals(AlarmType.LOW, start.type());
        assertEquals(Integer.valueOf(60), start.view().sgv());
    }

    // ------------------------------------------------------------------ IR-38

    @Test
    public void ir38_resetForgetsPerServerMemory() {
        Harness h = new Harness();
        h.tick();
        h.monitorMemory.reset();
        h.monitorState.failGet = true;
        int puts = h.monitorState.puts.size();
        h.clock.advanceMinutes(1);
        h.tick();
        assertEquals("no authoritative base after reset → no put", puts, h.monitorState.puts.size());

        h.alarmState.failPut = true;
        h.alarmMemory.write(h.alarmState, AlarmState.empty().keepRingingOnly());
        assertTrue(h.alarmMemory.dirty());
        h.alarmMemory.reset();
        assertFalse(h.alarmMemory.dirty());
        assertFalse(h.alarmMemory.known());
    }

    // ------------------------------------------------------------------ IR-39

    @Test
    public void ir39_refreshProjectsDirtyRingingAndLastKnownMonitorState() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));
        h.alarmState.failPut = true;                // the LOW ring state cannot be saved
        h.tick();
        assertNull(h.alarmState.value.activeType());
        h.monitorState.failGet = true;
        h.status.refresh();
        h.monitorExecutor.runAll();
        assertEquals(AlarmType.LOW, h.status.current().ringing());       // dirty memory wins
        assertEquals(NOW, h.status.current().lastCheckCompleted());     // not initial()
        assertEquals(FetchOutcome.STORAGE_ERROR, h.status.current().lastOutcome());
    }

    // ------------------------------------------------------------------ IR-40

    @Test
    public void ir40_settingsReadFailure_storageError_tickCompletes() {
        Harness h = new Harness();
        h.settings.fail = true;
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.STORAGE_ERROR, r.outcome());
        assertTrue(h.logger.events.contains("SYNC_FAIL:STORAGE_ERROR:STORAGE"));
        assertEquals(NOW, h.state().lastCheckCompleted());
    }

    @Test
    public void ir40_alarmStateWriteFailure_storageError_ringStillHappens() {
        Harness h = new Harness();
        h.readings.put(reading("r", NOW.minusSeconds(60), 60));
        h.alarmState.failPut = true;
        SyncResult r = h.tick();
        assertEquals(FetchOutcome.STORAGE_ERROR, r.outcome());
        assertEquals(FetchOutcome.STORAGE_ERROR, h.state().lastOutcome());
        assertEquals(FetchOutcome.STORAGE_ERROR, h.status.current().lastOutcome());
        assertEquals(NOW, h.state().lastSuccessfulCheck());              // steps 2–4 were OK
        assertEquals(AlarmType.LOW, ((AlarmCommand.Start) h.alarmController.last().get(0)).type());
        assertTrue(r.ringing());
    }

    @Test
    public void ir40_notifiedAlertStoreFailure_storageError() {
        Harness h = new Harness();
        h.entries.put(treatment("pa", NOW.minus(Duration.ofMinutes(5)), EntryType.PUMP_ALERT));
        h.notified.fail = true;
        assertEquals(FetchOutcome.STORAGE_ERROR, h.tick().outcome());
        assertTrue(h.pumpAlerts.posted.isEmpty());
    }

    // ------------------------------------------------------------------ IR-41

    @Test
    public void ir41_tickThrows_fetchingFalseStillPublished() {
        Harness h = new Harness();
        h.gateway.lastModified = () -> {
            throw new IllegalStateException("bug below the port");
        };
        try {
            h.tick();
            fail("expected the exception to propagate");
        } catch (IllegalStateException expected) {
            // the monitor loop handles it (CP-08)
        }
        assertFalse(h.status.current().fetching());
        assertTrue(h.snapshots.stream().anyMatch(s -> s.fetching()));
    }

    // ------------------------------------------------------------------ IR-42

    @Test
    public void ir42_noBackfillOnStorageErrorTick() {
        Harness h = new Harness();
        MonitorState s = Harness.steadyState(NOW);
        Map<NsCollection, CollectionCursor> cursors = new EnumMap<>(s.cursors());
        cursors.put(NsCollection.ENTRIES, new CollectionCursor(Harness.LM_ENTRIES, null, null,
                NOW.minus(Duration.ofHours(24)), false, null));
        h.monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                FetchOutcome.OK, null, s.lastReadingTime(), 60, List.of(), s.lastDailyMaintenance(), cursors);
        h.lastModified(NsCollection.ENTRIES, Harness.LM_ENTRIES + 1);
        h.gateway.readingsSince = ms -> page(true, List.of(reading("a", NOW.minusSeconds(30), 120)));
        h.readings.fail = true;
        assertEquals(FetchOutcome.STORAGE_ERROR, h.tick().outcome());
        assertEquals(0, h.gateway.count("readingsWindow"));
    }

    @Test
    public void ir42_stopThenStartOfSameType_newRingingSince() {
        AlarmState s = new AlarmState(AlarmType.LOW,
                Map.of(AlarmType.LOW, new AlarmTypeState(NOW.minusSeconds(600), null, 2, NOW.minusSeconds(600))), null);
        AlarmView v = new AlarmView(AlarmType.LOW, 60, Trend.FLAT, NOW.minusSeconds(30), null, null, null, 15);
        AlarmState out = AlarmStateTransitions.afterCommands(s, List.of(new AlarmCommand.Stop(AlarmType.LOW),
                new AlarmCommand.Start(AlarmType.LOW, v)), NOW);
        assertEquals(AlarmType.LOW, out.activeType());
        assertEquals(NOW, out.perType().get(AlarmType.LOW).ringingSince());
        assertEquals(NOW.minusSeconds(30), out.perType().get(AlarmType.LOW).triggerReadingTime());
    }

    @Test
    public void ir42_iobExactly15MinShown() {
        Harness h = new Harness();
        h.deviceStatus.rows.add(new DeviceStatus("d", NOW.minus(Duration.ofMinutes(15)), 130, "pump"));
        h.tick();
        assertEquals(Integer.valueOf(130), h.status.current().iobCenti());
    }
}
