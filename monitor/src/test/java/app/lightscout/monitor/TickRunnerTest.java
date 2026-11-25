package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.SyncResult;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * CP-08 §9: WF-01 step 6 (runtime part), watchdog arming after every tick (F2), „outage with live loop → No data
 * only”, „restarted service sees stale lastCheckCompleted”, heartbeat, F7 / Q-52, WF-05 not-configured stop,
 * JobScheduler net, TICK_LATE.
 */
public class TickRunnerTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(20);

    private Fakes.FakeClock clock;
    private Fakes.FakeAlarmPort port;
    private Fakes.RecordingLogger logger;
    private Fakes.FakeSyncCycle cycle;
    private Fakes.FakeMonitorStateStore monitorState;
    private Fakes.FakeAlarmStateStore alarmState;
    private Fakes.FakeFlags flags;
    private Fakes.FakeChannels channels;
    private Fakes.FakeJobNet jobNet;
    private Fakes.FakeService service;
    private Fakes.FakeWakeLock wakeLock;
    private Fakes.FakeBootCount bootCount;
    private TickRunner runner;
    private BootNotHandledGuard guard;

    @Before
    public void setUp() {
        clock = new Fakes.FakeClock();
        port = new Fakes.FakeAlarmPort();
        logger = new Fakes.RecordingLogger();
        cycle = new Fakes.FakeSyncCycle();
        monitorState = new Fakes.FakeMonitorStateStore();
        alarmState = new Fakes.FakeAlarmStateStore();
        flags = new Fakes.FakeFlags();
        channels = new Fakes.FakeChannels();
        jobNet = new Fakes.FakeJobNet();
        service = new Fakes.FakeService();
        wakeLock = new Fakes.FakeWakeLock();
        bootCount = new Fakes.FakeBootCount();
        cycle.lock = wakeLock;
        flags.handledBootCount = bootCount.count;   // this boot already handled unless a test says otherwise
        guard = new BootNotHandledGuard();
        runner = runner(wakeLock);
    }

    private TickRunner runner(RuntimePorts.WakeLock lock) {
        MonitorAlarms alarms = new MonitorAlarms(port, clock, logger, false);
        return new TickRunner(() -> cycle, monitorState, alarmState, new Fakes.FakeSettingsStore(), flags,
                channels, alarms, jobNet, new ServiceLauncher(service, alarms, logger), lock, bootCount, guard,
                clock, logger);
    }

    /** The fake sync cycle writes lastCheckCompleted = tick start like CP-02 (any outcome). */
    private void cycleWritesLastCheck(FetchOutcome outcome, boolean ringing) {
        cycle.onTick = r -> {
            Instant start = clock.now;
            monitorState.setChecks(monitorState.state.monitoringSince() != null
                    ? monitorState.state.monitoringSince() : start, start);
            return Fakes.result(outcome, clock.now.minusSeconds(30), null, ringing);
        };
    }

    // --- WF-01 step 6 (runtime part) -------------------------------------------------------------------------

    @Test
    public void wf01_wakeLockHeldDuringTick_andReleased() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        assertTrue(wakeLock.heldDuringRun);
        assertFalse(wakeLock.held);
        assertEquals(wakeLock.acquires, wakeLock.releases);
    }

    @Test
    public void wf01_wakeLockReleasedInFinally_evenWhenTheCycleThrows_andAlarmsStillArmed() {
        monitorState.setChecks(clock.now.minus(Duration.ofMinutes(3)), clock.now.minus(Duration.ofMinutes(3)));
        cycle.onTick = r -> {
            throw new IllegalStateException("boom");
        };
        runner.runTick(TickReason.SCHEDULED);
        assertFalse(wakeLock.held);
        assertTrue(port.armed(AlarmPort.Kind.TICK));                 // SR-37: retry plan
        assertEquals(Long.valueOf(clock.elapsed + 120_000L), port.trigger(AlarmPort.Kind.TICK));
        assertTrue(port.armed(AlarmPort.Kind.WATCHDOG));             // still watching the stale anchor
        assertEquals(1, logger.count("CRASH:IllegalStateException"));
    }

    @Test
    public void wf01_monitorNeverWritesIf13OrIf14() {
        cycleWritesLastCheck(FetchOutcome.NO_INTERNET, true);
        runner.runTick(TickReason.SCHEDULED);
        runner.runReevaluate();
        runner.runHeartbeat();
        runner.runWatchdog();
        assertEquals(0, monitorState.puts);
        assertEquals(0, alarmState.puts);
    }

    @Test
    public void wf01_exactRefused_inexactSetAndAllowWhileIdle() {
        port.canExact = false;
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        assertTrue(port.inexact.containsKey(AlarmPort.Kind.TICK));
        assertTrue(port.inexact.containsKey(AlarmPort.Kind.WATCHDOG));
        assertEquals(1, logger.count("EXACT_ALARM_DENIED"));
    }

    @Test
    public void nextTick_isTheSchedulePlan() {
        cycle.onTick = r -> Fakes.result(FetchOutcome.OK, clock.now, null, false);   // reading just now
        runner.runTick(TickReason.SCHEDULED);
        // E + 60 + 5 = 6 min 5 s (D-152)
        assertEquals(Long.valueOf(clock.elapsed + 365_000L), port.trigger(AlarmPort.Kind.TICK));
    }

    // --- Watchdog arming (F2) ----------------------------------------------------------------------------------

    @Test
    public void watchdog_armedAtLastCheckCompletedPlusThreshold_afterEveryTick_anyOutcome() {
        for (FetchOutcome outcome : new FetchOutcome[] {FetchOutcome.OK, FetchOutcome.NO_INTERNET,
                FetchOutcome.TOKEN_REJECTED, FetchOutcome.STORAGE_ERROR}) {
            cycleWritesLastCheck(outcome, false);
            runner.runTick(TickReason.SCHEDULED);
            long expected = clock.elapsed + THRESHOLD.toMillis();
            assertEquals(outcome.name(), Long.valueOf(expected), port.trigger(AlarmPort.Kind.WATCHDOG));
            clock.advance(Duration.ofMinutes(2));
        }
    }

    @Test
    public void watchdog_afterNotRunning_snoozed_rearmedAtSnoozeEnd() {
        Instant lcc = clock.now.minus(Duration.ofMinutes(40));
        monitorState.setChecks(lcc, lcc);
        alarmState.notRunningSnoozedUntil(clock.now.plus(Duration.ofMinutes(30)));
        cycle.onTick = r -> {
            throw new IllegalStateException("loop broken");
        };
        runner.runWatchdog();
        assertEquals(Long.valueOf(clock.elapsed + Duration.ofMinutes(30).toMillis()),
                port.trigger(AlarmPort.Kind.WATCHDOG));
    }

    @Test
    public void watchdog_afterNotRunning_stillStale_rearmedNowPlus5Min() {
        Instant lcc = clock.now.minus(Duration.ofMinutes(40));
        monitorState.setChecks(lcc, lcc);
        cycle.onTick = r -> {
            throw new IllegalStateException("loop broken");
        };
        runner.runWatchdog();
        assertEquals(Long.valueOf(clock.elapsed + Duration.ofMinutes(5).toMillis()),
                port.trigger(AlarmPort.Kind.WATCHDOG));
    }

    @Test
    public void watchdog_freshCheckMeanwhile_rearmsOnly_noEvaluationNoTick() {
        Instant lcc = clock.now.minus(Duration.ofMinutes(3));
        monitorState.setChecks(lcc, lcc);
        runner.runWatchdog();
        assertTrue(cycle.calls.isEmpty());
        assertEquals(0, logger.count("WATCHDOG_FIRE"));
        assertEquals(Long.valueOf(clock.elapsed + Duration.ofMinutes(17).toMillis()),
                port.trigger(AlarmPort.Kind.WATCHDOG));
    }

    @Test
    public void watchdog_stale_firesOnce_evaluatesFirst_thenTicks() {
        Instant lcc = clock.now.minus(Duration.ofMinutes(25));
        monitorState.setChecks(lcc, lcc);
        cycleWritesLastCheck(FetchOutcome.OK, true);
        runner.runWatchdog();
        assertEquals(List.of("reevaluate", "tick:WATCHDOG"), cycle.calls);
        assertEquals(1, logger.count("WATCHDOG_FIRE"));
        // the tick wrote a fresh lastCheckCompleted → normal arming again
        assertEquals(Long.valueOf(clock.elapsed + THRESHOLD.toMillis()), port.trigger(AlarmPort.Kind.WATCHDOG));
    }

    @Test
    public void outageWithLiveLoop_noDataOnly_watchdogNeverSeesStaleCheck() {
        // 2 h of failing ticks at the schedule cadence: every tick completes → lastCheckCompleted fresh.
        Instant lastReading = clock.now;
        monitorState.setChecks(clock.now, clock.now);
        cycle.onTick = r -> {
            Instant start = clock.now;
            monitorState.setChecks(monitorState.state.monitoringSince(), start);
            return Fakes.result(FetchOutcome.NO_INTERNET, lastReading, null, false);
        };
        Instant end = clock.now.plus(Duration.ofHours(2));
        while (clock.now.isBefore(end)) {
            runner.runTick(TickReason.SCHEDULED);
            long tickAt = port.trigger(AlarmPort.Kind.TICK);
            long watchdogAt = port.trigger(AlarmPort.Kind.WATCHDOG);
            assertTrue("watchdog must stay ahead of the next tick", watchdogAt > tickAt);
            clock.advance(Duration.ofMillis(tickAt - clock.elapsed));
        }
        assertEquals(0, logger.count("WATCHDOG_FIRE"));
    }

    @Test
    public void restartedServiceSeesStaleLastCheck_notRunningEvaluatedOnce() {
        // 08 §8.5 / R-68: the restart tick passes the stale check to IF-20 (which rings); the next tick is fresh.
        Instant lcc = clock.now.minus(Duration.ofMinutes(45));
        monitorState.setChecks(lcc, lcc);
        int[] staleSeen = {0};
        cycle.onTick = r -> {
            Instant prev = monitorState.state.lastCheckCompleted();
            if (!clock.now.isBefore(prev.plus(THRESHOLD))) {
                staleSeen[0]++;
            }
            monitorState.setChecks(monitorState.state.monitoringSince(), clock.now);
            return Fakes.result(FetchOutcome.OK, clock.now, null, staleSeen[0] > 0);
        };
        runner.runTick(TickReason.SERVICE_RESTART);
        clock.advance(Duration.ofMinutes(2));
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(1, staleSeen[0]);
    }

    @Test
    public void watchdog_neverStarted_notArmed() {
        cycle.onTick = r -> Fakes.result(FetchOutcome.OK, null, null, false);   // no anchor written
        runner.runTick(TickReason.SCHEDULED);
        assertFalse(port.armed(AlarmPort.Kind.WATCHDOG));
    }

    // --- Heartbeat (R-35) ---------------------------------------------------------------------------------------

    @Test
    public void heartbeat_armedEvery60sWhileRinging_cancelledWhenNothingRingsAfterSnooze() {
        cycleWritesLastCheck(FetchOutcome.OK, true);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(Long.valueOf(clock.elapsed + 60_000L), port.exact.get(AlarmPort.Kind.HEARTBEAT));

        clock.advance(Duration.ofSeconds(60));
        cycle.onReevaluate = () -> Fakes.result(FetchOutcome.OK, clock.now, null, true);
        runner.runHeartbeat();
        assertEquals(Long.valueOf(clock.elapsed + 60_000L), port.exact.get(AlarmPort.Kind.HEARTBEAT));
        assertEquals(1, logger.count("HEARTBEAT"));

        // snoozed meanwhile (AlarmActions): the next heartbeat evaluates nothing ringing → cancelled
        clock.advance(Duration.ofSeconds(60));
        cycle.onReevaluate = () -> Fakes.result(FetchOutcome.OK, clock.now, null, false);
        runner.runHeartbeat();
        assertFalse(port.armed(AlarmPort.Kind.HEARTBEAT));
    }

    @Test
    public void heartbeat_doesNotPostponeTheNetworkTick() {
        cycleWritesLastCheck(FetchOutcome.NO_INTERNET, true);
        runner.runTick(TickReason.SCHEDULED);
        long tick = port.trigger(AlarmPort.Kind.TICK);
        cycle.onReevaluate = () -> Fakes.result(FetchOutcome.NO_INTERNET, clock.now.minusSeconds(30), null, true);
        clock.advance(Duration.ofSeconds(30));
        runner.runHeartbeat();
        assertEquals(Long.valueOf(tick), port.trigger(AlarmPort.Kind.TICK));
    }

    @Test
    public void reevaluate_earlierNextEvaluation_movesTickEarlier() {
        cycle.onTick = r -> Fakes.result(FetchOutcome.OK, clock.now.minus(Duration.ofMinutes(25)), null, false);
        runner.runTick(TickReason.SCHEDULED);   // data loss → +5 min
        Instant soon = clock.now.plusSeconds(20);
        cycle.onReevaluate = () -> Fakes.result(FetchOutcome.OK, clock.now.minus(Duration.ofMinutes(25)), soon, false);
        runner.runReevaluate();
        assertEquals(Long.valueOf(clock.elapsed + 20_000L), port.trigger(AlarmPort.Kind.TICK));
    }

    @Test
    public void heartbeat_failedEvaluation_keepsRingAliveFromAlarmState() {
        alarmState.ringing(AlarmType.LOW);
        cycle.onReevaluate = () -> {
            throw new IllegalStateException("x");
        };
        runner.runHeartbeat();
        assertTrue(port.armed(AlarmPort.Kind.HEARTBEAT));
    }

    // --- F7 / Q-52 ----------------------------------------------------------------------------------------------

    @Test
    public void q52_channelBypassCheck_onlyWhenLastResultNotRinging() {
        cycleWritesLastCheck(FetchOutcome.OK, true);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(0, channels.bypassChecks);
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(1, channels.bypassChecks);
    }

    @Test
    public void q52_noBypassCheckWhenTickFailed_orOnReevaluate() {
        cycle.onTick = r -> {
            throw new IllegalStateException("x");
        };
        runner.runTick(TickReason.SCHEDULED);
        runner.runReevaluate();
        assertEquals(0, channels.bypassChecks);
    }

    // --- WF-05 / job net -----------------------------------------------------------------------------------------

    @Test
    public void notConfigured_noTick_allAlarmsAndJobCancelled_serviceStopped() {
        cycleWritesLastCheck(FetchOutcome.OK, true);
        service.running = true;
        runner.runTick(TickReason.SCHEDULED);
        assertTrue(jobNet.scheduled);
        flags.configured = false;
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(1, cycle.calls.size());
        for (AlarmPort.Kind k : AlarmPort.Kind.values()) {
            assertFalse(k.name(), port.armed(k));
        }
        assertFalse(jobNet.scheduled);
        assertFalse(service.running);
        assertFalse(wakeLock.held);
        assertEquals(0, flags.ceCalls);
    }

    @Test
    public void jobNet_ensuredOncePerProcess() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(1, jobNet.ensureCalls);
    }

    @Test
    public void onboardingDoneTick_recordsBootBaseline() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.ONBOARDING_DONE);
        assertEquals(bootCount.count, flags.handledBootCount);
    }

    @Test
    public void ir76_freshInstall_onboardingBaseline_noFalseBootNotHandled() {
        flags.handledBootCount = 0;                 // fresh install: DE default
        bootCount.count = 201;
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.ONBOARDING_DONE);
        assertEquals(201, flags.handledBootCount);
        assertFalse(new BootCountCheck(bootCount, flags, logger, guard).run());
        assertEquals(0, logger.count("BOOT_NOT_HANDLED"));
    }

    @Test
    public void ir76_firstCompletedTick_unhandledBoot_loggedOnceAndHealed() {
        flags.handledBootCount = 210;
        bootCount.count = 211;                      // BOOT_COMPLETED never recorded this boot (VR-28)
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.APP_OPEN);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(1, logger.count("BOOT_NOT_HANDLED"));
        assertEquals(211, flags.handledBootCount);
        assertFalse(new BootCountCheck(bootCount, flags, logger, guard).run());   // healed
        assertEquals(1, logger.count("BOOT_NOT_HANDLED"));
    }

    @Test
    public void q67_normalBoot_receiverMarkedProcess_noFalseLog_firstTickFindsMatch() {
        flags.handledBootCount = 300;
        bootCount.count = 301;
        guard.markBootHandled();                    // BootReceiver(BOOT_COMPLETED) in this process
        assertFalse(new BootCountCheck(bootCount, flags, logger, guard).run());   // app-start check races the write
        flags.handledBootCount = 301;               // receiver records before the service start
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.BOOT);
        assertEquals(0, logger.count("BOOT_NOT_HANDLED"));
        assertEquals(301, flags.handledBootCount);
    }

    @Test
    public void q67_bootBroadcastBlocked_appOpenCheckLogsOnce_firstTickHeals() {
        flags.handledBootCount = 310;
        bootCount.count = 311;
        assertTrue(new BootCountCheck(bootCount, flags, logger, guard).run());    // app open, unmarked process
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.APP_OPEN);
        assertEquals(1, logger.count("BOOT_NOT_HANDLED"));
        assertEquals(311, flags.handledBootCount);
        assertFalse(new BootCountCheck(bootCount, flags, logger, guard).run());   // job run later
        assertEquals(1, logger.count("BOOT_NOT_HANDLED"));
    }

    @Test
    public void q67_processDiesBeforeFirstTick_nextProcessLogsOnce() {
        flags.handledBootCount = 320;
        bootCount.count = 321;
        assertTrue(new BootCountCheck(bootCount, flags, logger, guard).run());    // process 1, then killed
        guard = new BootNotHandledGuard();                                        // process 2: fresh memory
        runner = runner(wakeLock);
        assertTrue(new BootCountCheck(bootCount, flags, logger, guard).run());    // job run in process 2
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.JOB_NET);
        assertEquals(2, logger.count("BOOT_NOT_HANDLED"));                        // once per process
        assertEquals(321, flags.handledBootCount);
    }

    @Test
    public void q68_cycleThrows_lockReleasedAndRefreshTimerCancelled() {
        boolean[] held = {false};
        int[] releases = {0};
        boolean[] timerCancelled = {false};
        int[] timersScheduled = {0};
        WakeLockSession session = new WakeLockSession(new WakeLockSession.Handle() {
            @Override
            public void acquire(long timeoutMs) {
                assertTrue(timeoutMs <= WakeLockBudget.ACQUIRE_MAX_MS);
                held[0] = true;
            }

            @Override
            public void release() {
                releases[0]++;
                held[0] = false;
            }

            @Override
            public boolean isHeld() {
                return held[0];
            }
        }, (task, periodMs) -> {
            timersScheduled[0]++;
            assertEquals(20_000L, periodMs);                                         // IR-77: literal value
            assertTrue(WakeLockBudget.REFRESH_PERIOD_MS < WakeLockBudget.ACQUIRE_MAX_MS);   // refresh before expiry
            return () -> timerCancelled[0] = true;
        }, clock::elapsedRealtimeMs);
        runner = runner(session);
        cycle.onTick = r -> {
            throw new IllegalStateException("network step failed");
        };
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(1, timersScheduled[0]);
        assertTrue(timerCancelled[0]);
        assertFalse(held[0]);
        assertEquals(1, releases[0]);
    }

    @Test
    public void ir76_failedTick_doesNotHeal_nextCompletedTickDoes() {
        flags.handledBootCount = 220;
        bootCount.count = 221;
        cycle.onTick = r -> {
            throw new IllegalStateException("x");
        };
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(220, flags.handledBootCount);
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(221, flags.handledBootCount);
    }

    @Test
    public void ir76_handledBoot_firstTickWritesNothing() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.BOOT);
        assertEquals(0, flags.bootCountWrites);
        assertEquals(0, logger.count("BOOT_NOT_HANDLED"));
    }

    @Test
    public void ir70_flagReadThrows_tickRuns_nothingCancelledOrStopped() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        service.running = true;
        runner.runTick(TickReason.SCHEDULED);
        flags.throwOnRead = true;
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(2, cycle.calls.size());
        assertTrue(port.armed(AlarmPort.Kind.TICK));
        assertTrue(port.armed(AlarmPort.Kind.WATCHDOG));
        assertFalse(port.calls.contains("cancel:TICK"));
        assertTrue(jobNet.scheduled);
        assertTrue(service.running);
    }

    @Test
    public void ir73_wakeLockRefreshedBeforeNetworkAndArmingSteps_whileHeld() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(2, wakeLock.refreshesWhileHeld);
        assertFalse(wakeLock.held);
    }

    @Test
    public void ir66_loopBreak_tickNotRearmed_watchdogStillArmed() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        LoopBreakSwitch.set(true);
        try {
            runner.runTick(TickReason.SCHEDULED);
            assertFalse(port.armed(AlarmPort.Kind.TICK));
            assertTrue(port.armed(AlarmPort.Kind.WATCHDOG));
        } finally {
            LoopBreakSwitch.set(false);
        }
        runner.runTick(TickReason.SCHEDULED);
        assertTrue(port.armed(AlarmPort.Kind.TICK));
    }

    @Test
    public void q58_noReading_scheduleAnchoredOnMonitoringSince() {
        Instant since = clock.now.minus(Duration.ofMinutes(25));   // first-use threshold passed → 5 min
        monitorState.setChecks(since, clock.now);
        cycle.onTick = r -> Fakes.result(FetchOutcome.OK, null, null, false);
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(Long.valueOf(clock.elapsed + Duration.ofMinutes(5).toMillis()), port.trigger(AlarmPort.Kind.TICK));
    }

    // --- TICK_LATE (VR-07 evidence) ----------------------------------------------------------------------------

    @Test
    public void tickLate_loggedWhenScheduledTickRunsMoreThan10sLate() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        long planned = port.trigger(AlarmPort.Kind.TICK);
        clock.advance(Duration.ofMillis(planned - clock.elapsed + 12_000L));
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(List.of("TICK_LATE:12000"), logger.lines.stream().filter(l -> l.startsWith("TICK_LATE")).toList());
    }

    @Test
    public void debugTiming_oneLinePerTick_reasonAndDurationsOnly() {
        cycle.onTick = r -> {
            clock.advance(Duration.ofSeconds(47));   // the IF-20 cycle takes 47 s
            return Fakes.result(FetchOutcome.OK, clock.now, null, false);
        };
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(List.of("TickTiming tick SCHEDULED cycle=47000ms step6=0ms"), logger.debugLines);
    }

    @Test
    public void tickOnTime_notLogged() {
        cycleWritesLastCheck(FetchOutcome.OK, false);
        runner.runTick(TickReason.SCHEDULED);
        long planned = port.trigger(AlarmPort.Kind.TICK);
        clock.advance(Duration.ofMillis(planned - clock.elapsed + 3_000L));
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(0, logger.count("TICK_LATE"));
    }

    @Test
    public void syncResultNoDataMinutes_drivesWatchdogThreshold() {
        cycle.onTick = r -> {
            monitorState.setChecks(clock.now, clock.now);
            return new SyncResult(FetchOutcome.OK, false, clock.now, null, false, 60, 45);
        };
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(Long.valueOf(clock.elapsed + Duration.ofMinutes(45).toMillis()),
                port.trigger(AlarmPort.Kind.WATCHDOG));
    }

    /**
     * BUG-18 N1: the clock advances during {@code runOnce} (post-publish maintenance / backfill); a snooze end that
     * fell due meanwhile arms the tick at once instead of being dropped by the A-3 guard.
     */
    @Test
    public void n1_evaluationDueDuringRunOnce_tickArmedImmediately() {
        cycle.onTick = r -> {
            Instant evaluatedAt = clock.now;
            clock.advance(Duration.ofSeconds(40));                    // maintenance + backfill after publish
            monitorState.setChecks(evaluatedAt, evaluatedAt);
            return Fakes.result(FetchOutcome.OK, evaluatedAt.minusSeconds(30), evaluatedAt.plusSeconds(10), true);
        };
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(Long.valueOf(clock.elapsed), port.trigger(AlarmPort.Kind.TICK));
    }

    /** BUG-18 N1 control: an evaluation instant still in the future after the tick is armed at that instant. */
    @Test
    public void n1_evaluationAfterTheTick_armedAtIt() {
        cycle.onTick = r -> {
            Instant evaluatedAt = clock.now;
            clock.advance(Duration.ofSeconds(40));
            monitorState.setChecks(evaluatedAt, evaluatedAt);
            return Fakes.result(FetchOutcome.OK, evaluatedAt.minusSeconds(30), evaluatedAt.plusSeconds(60), true);
        };
        runner.runTick(TickReason.SCHEDULED);
        assertEquals(Long.valueOf(clock.elapsed + 20_000L), port.trigger(AlarmPort.Kind.TICK));
    }
}
