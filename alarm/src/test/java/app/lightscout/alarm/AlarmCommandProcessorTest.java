package app.lightscout.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.alarm.AlarmView;
import app.lightscout.usecases.alarm.AlarmStateMemory;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * IF-16 command handling end-to-end in the JVM (real {@link RingEngine} and {@link AlarmSurfaceController}, fakes for
 * the store, volume, output, notifications): CP-07 §9 rows „volume save / restore (F8)”, „user volume change during
 * ringing”, „Replace”, „Update”, „condition clears while ringing (AL-4)”, plus the Moderator ordering rule (Stop(X)
 * then Start(Y) in one {@code apply}; restore only at the end), R-35 idempotent Start / resume / empty-apply restore
 * point, F16 wake lock and the snooze request.
 */
public class AlarmCommandProcessorTest {

    private Fakes f;
    private AlarmStateMemory memory;
    private RingEngine engine;
    private AlarmSurfaceController surface;
    private AlarmScreenModel screen;
    private AlarmCommandProcessor processor;

    @Before
    public void setUp() {
        f = new Fakes();
        memory = new AlarmStateMemory();
        screen = new AlarmScreenModel();
        surface = new AlarmSurfaceController(f.notifications, f.channels, screen, f.screenOff);
        processor = newProcessor();
    }

    private AlarmCommandProcessor newProcessor() {
        AlarmCommandProcessor[] self = new AlarmCommandProcessor[1];
        engine = new RingEngine(f.volume, f.output, f.wakeLock, f.scheduler, f.logger,
                (type, step) -> self[0].onStep(type, step),   // the runtime posts this to monitorExecutor
                f.clock::elapsedRealtimeMs);
        self[0] = new AlarmCommandProcessor(new AlarmStateAccess(memory, f.store), engine, surface, f.logger);
        return self[0];
    }

    /** What CP-02 does before calling apply: persist activeType / ringingSince (R-35). */
    private void persistRinging(AlarmType type) {
        AlarmState s = memory.read(f.store);
        memory.write(f.store, new AlarmState(type,
                Map.of(type, new AlarmTypeState(Fakes.T0, null, AlarmStateEdits.step(s, type), Fakes.T0)),
                s.savedUserAlarmVolume()));
        f.ops.clear();
    }

    private void start(AlarmType type) {
        persistRinging(type);
        processor.apply(List.of(new AlarmCommand.Start(type, Fakes.view(type, 52))));
    }

    @Test
    public void start_commitsSavedUserVolume_beforeFirstVolumeChange() {
        f.volume.current = 5;
        start(AlarmType.LOW);
        assertEquals("put(saved=5)", f.ops.get(0));
        int firstSet = f.ops.indexOf("set(6)");
        assertTrue(firstSet > 0);
        assertEquals(Integer.valueOf(5), f.store.state.savedUserAlarmVolume());
        assertEquals(AlarmType.LOW, processor.ringing());
        assertTrue(f.logger.events.contains("ALARM_RING:LOW"));
    }

    @Test
    public void start_postsSilentNotificationWithFsi_publishesScreen_holdsWakeLock() {
        start(AlarmType.URGENT_LOW);
        assertEquals(List.of("cancel", "post(alarms,URGENT_LOW,fsi)"), f.notifications.log);
        assertEquals(AlarmType.URGENT_LOW, screen.current().type());
        assertTrue(f.wakeLock.held);
        assertTrue(f.output.vibrating);
        assertEquals(1, f.screenOff.registers);
    }

    @Test
    public void stop_whenNothingElseRings_restoresSavedVolume_andClearsIt() {
        f.volume.current = 5;
        start(AlarmType.LOW);
        f.scheduler.advance(30_000);
        assertEquals(9, f.volume.current);   // 40 % of 15 = 6, +10 % twice → 9
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.LOW)));
        assertEquals(5, f.volume.current);
        assertNull(f.store.state.savedUserAlarmVolume());
        assertNull(processor.ringing());
        assertFalse(f.wakeLock.held);
        assertFalse(f.output.vibrating);
        assertEquals(0, f.notifications.active());
        assertNull(screen.current());
        assertTrue(f.logger.events.contains("ALARM_STOP:LOW"));
    }

    @Test
    public void stopThenStartInOneApply_isNotARestorePoint() {
        f.volume.current = 5;
        start(AlarmType.HIGH);
        f.scheduler.advance(45_000);
        int escalated = f.volume.current;
        assertTrue(escalated > 6);
        f.ops.clear();
        persistRinging(AlarmType.LOW);
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.HIGH),
                new AlarmCommand.Start(AlarmType.LOW, Fakes.view(AlarmType.LOW, 66))));
        assertEquals(AlarmType.LOW, processor.ringing());
        assertFalse(f.volume.sets.contains(5));   // never restored in the middle of the list
        assertEquals(Integer.valueOf(5), f.store.state.savedUserAlarmVolume());   // the user's value is kept
        assertEquals(6, f.volume.current);   // LOW starts at step 0 of the user's schedule
        assertEquals(0, engine.currentStep());
        assertEquals(AlarmType.LOW, f.notifications.posted.type());
    }

    @Test
    public void stopOfAnotherType_whileOneRings_doesNotRestore() {
        f.volume.current = 5;
        start(AlarmType.LOW);
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.HIGH)));
        assertEquals(AlarmType.LOW, processor.ringing());
        assertEquals(Integer.valueOf(5), f.store.state.savedUserAlarmVolume());
        assertFalse(f.volume.sets.contains(5));
        assertTrue(f.output.vibrating);
    }

    @Test
    public void emptyApply_withSavedVolume_andNothingRinging_restores() {
        // a previous process died while ringing with the volume raised; the alarm was snoozed meanwhile
        f.store.state = new AlarmState(null, Map.of(), 4);
        f.volume.current = 15;
        processor.apply(List.of());
        assertEquals(4, f.volume.current);
        assertNull(f.store.state.savedUserAlarmVolume());
    }

    @Test
    public void emptyApply_withoutSavedVolume_changesNothing() {
        f.volume.current = 9;
        processor.apply(List.of());
        assertTrue(f.volume.sets.isEmpty());
        assertEquals(0, f.store.puts);
    }

    @Test
    public void replace_keepsEscalationLevel_cancelsReplacedNotification_oneAtATime() {
        f.volume.current = 5;
        start(AlarmType.LOW);
        f.scheduler.advance(30_000);
        int step = engine.currentStep();
        int level = f.volume.current;
        f.notifications.log.clear();
        persistRinging(AlarmType.URGENT_LOW);
        processor.apply(List.of(new AlarmCommand.Replace(AlarmType.LOW, AlarmType.URGENT_LOW,
                Fakes.view(AlarmType.URGENT_LOW, 52))));
        assertEquals(AlarmType.URGENT_LOW, processor.ringing());
        assertEquals(step, engine.currentStep());
        assertEquals(level, f.volume.current);
        assertEquals(List.of("cancel", "post(alarms,URGENT_LOW,fsi)"), f.notifications.log);
        assertEquals(1, f.notifications.active());
        assertEquals(AlarmType.URGENT_LOW, screen.current().type());
        assertEquals(step, AlarmStateEdits.step(f.store.state, AlarmType.URGENT_LOW));
        assertTrue(f.logger.events.contains("ALARM_REPLACE:LOW>URGENT_LOW"));
        assertEquals(1, f.output.plays);   // sound continues, no restart
    }

    @Test
    public void update_refreshesContent_ringingContinues_noNewEscalationStart() {
        start(AlarmType.LOW);
        f.scheduler.advance(15_000);
        int plays = f.output.plays;
        int step = engine.currentStep();
        f.notifications.log.clear();
        AlarmView newer = Fakes.view(AlarmType.LOW, 61);
        processor.apply(List.of(new AlarmCommand.Update(newer)));
        assertEquals(List.of("post(alarms,LOW,once)"), f.notifications.log);
        assertEquals(Integer.valueOf(61), screen.current().sgv());
        assertEquals(plays, f.output.plays);
        assertEquals(step, engine.currentStep());
        assertTrue(engine.active());
        assertEquals(2, f.wakeLock.acquires);   // F16 re-acquired with the keep-alive
    }

    @Test
    public void conditionClears_runtimeNeverStopsByItself() {
        start(AlarmType.LOW);
        // the evaluator keeps emitting Update for the ringing type even when the value recovered (AL-4)
        for (int i = 0; i < 30; i++) {
            f.scheduler.advance(60_000);
            processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 120))));
        }
        processor.apply(List.of());
        assertEquals(AlarmType.LOW, processor.ringing());
        assertTrue(engine.active());
        assertTrue(f.output.vibrating);
        assertEquals(1, f.notifications.active());
    }

    @Test
    public void start_isIdempotent() {
        start(AlarmType.LOW);
        processor.apply(List.of(new AlarmCommand.Start(AlarmType.LOW, Fakes.view(AlarmType.LOW, 60))));
        assertEquals(1, f.output.plays);
        assertEquals(1, f.logger.events.stream().filter(e -> e.equals("ALARM_RING:LOW")).count());
    }

    @Test
    public void resumeAfterProcessDeath_continuesAtPersistedStep_keepsSavedVolume() {
        f.volume.current = 15;   // raised by the previous process
        f.store.state = new AlarmState(AlarmType.NO_DATA,
                Map.of(AlarmType.NO_DATA, new AlarmTypeState(Fakes.T0, null, 4, null)), 3);
        processor.apply(List.of(new AlarmCommand.Start(AlarmType.NO_DATA, Fakes.noData(null))));
        assertEquals(4, engine.currentStep());
        assertEquals(Integer.valueOf(3), f.store.state.savedUserAlarmVolume());   // not overwritten by 15
        assertEquals(EscalationSchedule.level(3, 1, 15, 4), f.volume.current);
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.NO_DATA)));
        assertEquals(3, f.volume.current);
    }

    @Test
    public void newAlarmAfterSnooze_startsAtStepZero() {
        f.store.state = new AlarmState(null,
                Map.of(AlarmType.LOW, new AlarmTypeState(null, Fakes.T0.minusSeconds(60), 5, null)), null);
        start(AlarmType.LOW);
        assertEquals(0, engine.currentStep());
        assertEquals(0, AlarmStateEdits.step(f.store.state, AlarmType.LOW));
    }

    @Test
    public void userVolumeChangeDuringRinging_stopsEscalation_restoreStillUsesSavedValue() {
        f.volume.current = 5;
        start(AlarmType.HIGH);
        f.scheduler.advance(15_000);
        f.volume.current = 2;   // user change
        f.scheduler.advance(60_000);
        assertEquals(2, f.volume.current);
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.HIGH)));
        assertEquals(5, f.volume.current);
    }

    @Test
    public void persistFailure_stillRings_restoreUsesValueKeptInMemory() {
        memory.read(f.store);   // a real state is known
        f.store.failPut = true;
        f.volume.current = 5;
        processor.apply(List.of(new AlarmCommand.Start(AlarmType.URGENT_LOW, Fakes.view(AlarmType.URGENT_LOW, 50))));
        assertTrue(engine.active());
        f.scheduler.advance(30_000);
        f.store.failPut = false;
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.URGENT_LOW)));
        assertEquals(5, f.volume.current);
    }

    @Test
    public void escalationStep_persistedForTheRingingType() {
        start(AlarmType.LOW);
        f.scheduler.advance(45_000);
        assertEquals(3, AlarmStateEdits.step(f.store.state, AlarmType.LOW));
    }

    @Test
    public void snoozeRequest_silencesAtOnce_keepAliveDoesNotRestart_untilStop() {
        start(AlarmType.LOW);
        processor.requestSnooze(AlarmType.LOW);
        assertFalse(engine.active());
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 60))));
        assertFalse(engine.active());
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.LOW)));
        assertNull(processor.ringing());
        assertFalse(f.wakeLock.held);
        assertEquals(0, f.notifications.active());
    }

    @Test
    public void snoozeWhileTickBlocks100s_noReRing() {
        start(AlarmType.LOW);
        processor.requestSnooze(AlarmType.LOW);
        f.clock.advanceMs(100_000);   // a tick holds monitorExecutor; its keep-alive runs before the snooze task
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 60))));
        processor.apply(List.of(new AlarmCommand.Start(AlarmType.LOW, Fakes.view(AlarmType.LOW, 60))));
        assertFalse(engine.active());
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.LOW)));   // the snooze task finally runs
        assertNull(processor.ringing());
        assertFalse(engine.active());
    }

    @Test
    public void snoozeRequest_stillSilentJustBeforeThreeMinutes() {
        start(AlarmType.LOW);
        processor.requestSnooze(AlarmType.LOW);
        f.clock.advanceMs(RingEngine.SNOOZE_SILENCE_GUARD_MS - 1);
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 60))));
        assertFalse(engine.active());
    }

    @Test
    public void snoozeRequest_withoutStopWithin3min_ringsAgain() {
        assertEquals(180_000L, RingEngine.SNOOZE_SILENCE_GUARD_MS);
        start(AlarmType.LOW);
        processor.requestSnooze(AlarmType.LOW);
        f.clock.advanceMs(RingEngine.SNOOZE_SILENCE_GUARD_MS);
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 60))));
        assertTrue(engine.active());
    }

    @Test
    public void startOfOtherTypeDuringSilencedWindow_ringsAtOnce_staleStopIgnored() {
        start(AlarmType.HIGH);
        processor.requestSnooze(AlarmType.HIGH);
        assertFalse(engine.active());
        persistRinging(AlarmType.NO_DATA);
        processor.apply(List.of(new AlarmCommand.Start(AlarmType.NO_DATA, Fakes.noData(null))));
        assertTrue(engine.active());
        assertEquals(AlarmType.NO_DATA, processor.ringing());
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.HIGH)));   // stale Stop(X) while Y plays
        assertEquals(AlarmType.NO_DATA, processor.ringing());
        assertTrue(engine.active());
        assertTrue(f.wakeLock.held);
    }

    @Test
    public void updateOfY_whileStaleSnoozeOfXPending_renewsWakeLock_keepsPlaying() {
        start(AlarmType.LOW);
        processor.requestSnooze(AlarmType.LOW);
        persistRinging(AlarmType.URGENT_LOW);
        processor.apply(List.of(new AlarmCommand.Replace(AlarmType.LOW, AlarmType.URGENT_LOW,
                Fakes.view(AlarmType.URGENT_LOW, 50))));
        int acquires = f.wakeLock.acquires;
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.URGENT_LOW, 48))));
        assertTrue(engine.active());
        assertEquals(AlarmType.URGENT_LOW, engine.type());
        assertEquals(acquires + 1, f.wakeLock.acquires);   // F16 renewed for Y
        assertTrue(f.wakeLock.held);
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.LOW)));   // the stale snooze Stop(X)
        assertTrue(engine.active());
        assertEquals(AlarmType.URGENT_LOW, processor.ringing());
    }

    @Test
    public void missingSavedVolumeWhileRinging_noNpe_usesCurrentVolume() {
        start(AlarmType.LOW);
        f.scheduler.advance(15_000);
        AlarmState s = memory.read(f.store);
        memory.write(f.store, AlarmStateEdits.withSavedVolume(s, null));   // another writer dropped it (IR-86)
        engine.stop();   // force the keep-alive to restart from a base volume
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 60))));
        persistRinging(AlarmType.URGENT_LOW);
        processor.apply(List.of(new AlarmCommand.Replace(AlarmType.LOW, AlarmType.URGENT_LOW,
                Fakes.view(AlarmType.URGENT_LOW, 50))));
        assertTrue(engine.active());
        assertEquals(AlarmType.URGENT_LOW, processor.ringing());
    }

    @Test
    public void snoozeRace_keepAliveBeforeOrAfterSilence_staysSilent() {
        // order 1: the keep-alive reaches the player thread first, then the snooze's silence
        start(AlarmType.LOW);
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 60))));
        processor.requestSnooze(AlarmType.LOW);
        assertFalse(engine.active());
        // order 2: silence first, then a keep-alive already queued on monitorExecutor — checked atomically in the
        // engine on the player thread (IR-87)
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.LOW, 60))));
        engine.keepAlive(AlarmType.LOW, 0, 5);
        assertFalse(engine.active());
        assertFalse(f.wakeLock.held);
    }

    @Test
    public void snoozeRequestOfReplacedType_higherTypeStillRings() {
        start(AlarmType.LOW);
        processor.requestSnooze(AlarmType.LOW);
        assertFalse(engine.active());
        persistRinging(AlarmType.URGENT_LOW);
        processor.apply(List.of(new AlarmCommand.Replace(AlarmType.LOW, AlarmType.URGENT_LOW,
                Fakes.view(AlarmType.URGENT_LOW, 50))));
        assertTrue(engine.active());
        processor.apply(List.of(new AlarmCommand.Stop(AlarmType.LOW)));   // the IF-25 Stop of the snoozed type
        assertEquals(AlarmType.URGENT_LOW, processor.ringing());
        assertTrue(engine.active());
    }

    @Test
    public void updateWithNothingRinging_startsTheType() {
        persistRinging(AlarmType.HIGH);
        processor.apply(List.of(new AlarmCommand.Update(Fakes.view(AlarmType.HIGH, 262))));
        assertEquals(AlarmType.HIGH, processor.ringing());
        assertTrue(engine.active());
        assertNotNull(f.store.state.savedUserAlarmVolume());
    }

    @Test
    public void replaceWithNothingRinging_startsTheNewType() {
        persistRinging(AlarmType.URGENT_LOW);
        processor.apply(List.of(new AlarmCommand.Replace(AlarmType.LOW, AlarmType.URGENT_LOW,
                Fakes.view(AlarmType.URGENT_LOW, 50))));
        assertEquals(AlarmType.URGENT_LOW, processor.ringing());
        assertTrue(engine.active());
    }

    @Test
    public void notificationFailure_neverPreventsSound() {
        AlarmSurface failing = new AlarmSurface() {
            @Override
            public void show(AlarmView view) {
                throw new IllegalStateException("notifications off");
            }

            @Override
            public void replace(AlarmView view) {
                throw new IllegalStateException("notifications off");
            }

            @Override
            public void update(AlarmView view) {
                throw new IllegalStateException("notifications off");
            }

            @Override
            public void close() {
                throw new IllegalStateException("notifications off");
            }
        };
        AlarmCommandProcessor p = new AlarmCommandProcessor(new AlarmStateAccess(memory, f.store), engine, failing,
                f.logger);
        p.apply(List.of(new AlarmCommand.Start(AlarmType.LOW, Fakes.view(AlarmType.LOW, 60))));
        assertTrue(engine.active());
        assertTrue(f.output.vibrating);
    }
}
