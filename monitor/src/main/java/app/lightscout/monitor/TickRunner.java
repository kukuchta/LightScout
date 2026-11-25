package app.lightscout.monitor;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.alarm.WatchdogPolicy;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.TickReason;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.usecases.SyncCycle;
import app.lightscout.usecases.SyncResult;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Runtime host of WF-01 steps 1 and 6, WF-03 and the ringing heartbeat (CP-08 §6). Every public method runs on
 * {@code monitorExecutor} only (CP-00 §4); {@link DefaultMonitorControl} and {@link MonitorRuntime} post to it.
 *
 * <p>Per tick: wake lock (≤ 30 s, released in {@code finally}) → IF-20 {@code runOnce}: steps 2–4 (fetch), step 5
 * (alarm evaluation, IF-16, snapshot), the step-6 monitor-state write (CP-00A R-24), then the post-publish phase
 * (WF-12 daily maintenance / SY-4 reconcile, SY-8 backfill and a second monitor-state write — Q-101) → next tick
 * {@code min(schedule, nextEvaluation)} (F12; an evaluation instant that fell due while {@code runOnce} was still
 * running is armed at once — BUG-18 N1) → watchdog at
 * {@code lastCheckCompleted + threshold} (F2) → heartbeat on / off ({@code SyncResult.ringing}, R-35) → CH-01
 * bypass check only when nothing rings (F7, Q-52) → JobScheduler net present. This class never writes IF-13 /
 * IF-14 (it only reads them). An unexpected exception in a tick never stops monitoring (SR-37): the alarms are
 * re-armed with a retry plan.
 *
 * <p>Wake lock (Q-65 / IR-73): this runner owns its own timed lock: acquired for at most 30 s at entry, refreshed
 * (at most 30 s each) before the IF-20 call and before step 6, under a 120 s hard cap per tick, released in
 * {@code finally}. IF-20 offers no per-network-step callback, so the adapter also refreshes every 20 s while held,
 * within the same cap. A long post-publish phase (the daily 31-day reconcile on a slow network) can exceed the
 * 120 s cap; the alarms and the snapshot are already done by then, and the CPU may then sleep inside the
 * remaining network calls, which only delays the end of the tick (the next tick is armed after {@code runOnce}).
 */
final class TickRunner {

    /** VR-07 lateness criterion: a scheduled tick more than 10 s late is logged as {@code TICK_LATE}. */
    static final long LATE_THRESHOLD_MS = 10_000L;
    /** A re-evaluation re-arms the tick when the armed one is this far in the past (it never ran). */
    static final Duration MISSED_TICK_GRACE = Duration.ofMinutes(2);
    /**
     * Logcat tag of the debug-only tick timing line {@code tick <REASON> cycle=<ms>ms step6=<ms>ms} (IF-20
     * {@code runOnce}, then step-6 arming / heartbeat / F7 / job net). The phase split inside {@code runOnce} is
     * logged by {@code :usecases} ({@code SyncTiming}), the per-call split by {@code :nightscout} ({@code NsTiming}).
     */
    static final String TIMING_TAG = "TickTiming";

    private final Supplier<SyncCycle> syncCycle;
    private final MonitorStateStore monitorState;
    private final AlarmStateStore alarmState;
    private final SettingsStore settings;
    private final AppFlagsStore flags;
    private final NotificationChannels channels;
    private final MonitorAlarms alarms;
    private final RuntimePorts.JobNet jobNet;
    private final ServiceLauncher launcher;
    private final RuntimePorts.WakeLock wakeLock;
    private final RuntimePorts.BootCountSource bootCount;
    private final BootNotHandledGuard bootGuard;
    private final Clock clock;
    private final Logger logger;

    private final TickSchedule schedule = new TickSchedule();
    private final WatchdogSchedule watchdogSchedule = new WatchdogSchedule();
    private final WatchdogPolicy watchdogPolicy = new WatchdogPolicy();

    private volatile SyncResult lastResult;
    private Instant armedTickAt;
    private Long plannedTickElapsed;
    private boolean jobEnsured;
    private boolean bootChecked;

    /**
     * Creates the runner.
     *
     * @param syncCycle    IF-20 (lazy: breaks the IF-21 → IF-62 → IF-20 construction cycle in the container)
     * @param monitorState IF-13 (read only)
     * @param alarmState   IF-14 (read only)
     * @param settings     IF-11 (read only; threshold before the first result)
     * @param flags        IF-60 ({@code monitoringConfigured})
     * @param channels     CMP-26 ({@code ensureAlarmChannelBypass}, F7)
     * @param alarms       alarm scheduler
     * @param jobNet       JobScheduler net
     * @param launcher     service launcher (stop)
     * @param wakeLock     sync wake lock
     * @param bootCount    {@code Settings.Global.BOOT_COUNT} (F5 baseline at onboarding)
     * @param bootGuard    process-scoped {@code BOOT_NOT_HANDLED} guard (Q-67)
     * @param clock        IF-15
     * @param logger       IF-66
     */
    TickRunner(Supplier<SyncCycle> syncCycle, MonitorStateStore monitorState, AlarmStateStore alarmState,
               SettingsStore settings, AppFlagsStore flags, NotificationChannels channels, MonitorAlarms alarms,
               RuntimePorts.JobNet jobNet, ServiceLauncher launcher, RuntimePorts.WakeLock wakeLock,
               RuntimePorts.BootCountSource bootCount, BootNotHandledGuard bootGuard, Clock clock, Logger logger) {
        this.syncCycle = syncCycle;
        this.monitorState = monitorState;
        this.alarmState = alarmState;
        this.settings = settings;
        this.flags = flags;
        this.channels = channels;
        this.alarms = alarms;
        this.jobNet = jobNet;
        this.launcher = launcher;
        this.wakeLock = wakeLock;
        this.bootCount = bootCount;
        this.bootGuard = bootGuard;
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * Runs one sync tick (WF-01).
     *
     * @param reason why
     */
    void runTick(TickReason reason) {
        wakeLock.acquire();
        try {
            if (!monitoringConfigured()) {
                stopAll();
                return;
            }
            tick(reason);
        } finally {
            wakeLock.release();
        }
    }

    /**
     * Re-evaluates alarms without network (IF-62 {@code reevaluateAlarms}, CP-00A R-24).
     */
    void runReevaluate() {
        wakeLock.acquire();
        try {
            if (!monitoringConfigured()) {
                stopAll();
                return;
            }
            reevaluate();
        } finally {
            wakeLock.release();
        }
    }

    /**
     * Ringing heartbeat (ADR-067, F3, R-35): re-evaluate → {@code Update} keep-alive through IF-16, which resumes
     * the player and re-acquires its wake lock (F16); the heartbeat is re-armed while anything rings.
     */
    void runHeartbeat() {
        wakeLock.acquire();
        try {
            if (!monitoringConfigured()) {
                stopAll();
                return;
            }
            logger.event(EventCode.HEARTBEAT);
            reevaluate();
        } finally {
            wakeLock.release();
        }
    }

    /**
     * Watchdog fired (WF-03 steps 2–4 after the receiver started the service — F3). Fresh check meanwhile →
     * re-arm and end. Stale → {@code WATCHDOG_FIRE}, evaluate (NOT_RUNNING rings through IF-01 → IF-16; AL-3 /
     * AL-6 apply — F20), then a tick, which re-arms everything.
     */
    void runWatchdog() {
        wakeLock.acquire();
        try {
            if (!monitoringConfigured()) {
                stopAll();
                return;
            }
            Instant now = clock.now();
            Duration threshold = threshold(null);
            MonitorState ms = readMonitorState();
            if (ms != null) {
                Instant anchor = anchor(ms);
                if (!watchdogPolicy.isNotRunning(now, anchor, threshold)) {
                    Instant at = watchdogSchedule.armAt(now, anchor, threshold, null);
                    if (at != null) {
                        alarms.armWatchdog(at);
                    }
                    return;
                }
            }
            logger.event(EventCode.WATCHDOG_FIRE);
            reevaluate();
            tick(TickReason.WATCHDOG);
        } finally {
            wakeLock.release();
        }
    }

    /**
     * Last known ringing state of this process (any thread).
     *
     * @return {@code SyncResult.ringing} of the last completed evaluation, {@code false} if none yet
     */
    boolean lastKnownRinging() {
        SyncResult r = lastResult;
        return r != null && r.ringing();
    }

    private void tick(TickReason reason) {
        noteLateness(reason);
        if (reason == TickReason.ONBOARDING_DONE) {
            recordBootBaseline();
        }
        Instant start = clock.now();
        long cycleStart = clock.elapsedRealtimeMs();
        SyncResult result = null;
        wakeLock.refresh();   // before the IF-20 network steps (Q-65)
        try {
            result = syncCycle.get().runOnce(reason);
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
        long cycleEnd = clock.elapsedRealtimeMs();
        wakeLock.refresh();   // step 6: arming
        if (result != null) {
            healBootCount();
        }
        Instant now = clock.now();
        TickPlan plan = result != null ? schedule.next(start, now, result, firstUseAnchor())
                : schedule.afterFailure(now);
        armTick(plan);
        armWatchdog(now, threshold(result), result != null ? start : null);
        updateHeartbeat(result);
        if (result != null) {
            lastResult = result;
            if (!result.ringing()) {
                ensureAlarmChannelBypass();   // F7 on every idle tick; never while ringing (Q-52)
            }
        }
        ensureJobNet();
        // Debug builds only (Logger.debug: dropped in release, stripped by R8): reason and durations (SR-07).
        logger.debug(TIMING_TAG, "tick " + reason.name() + " cycle=" + (cycleEnd - cycleStart) + "ms step6="
                + (clock.elapsedRealtimeMs() - cycleEnd) + "ms");
    }

    private void reevaluate() {
        SyncResult result = null;
        Instant start = clock.now();
        wakeLock.refresh();
        try {
            result = syncCycle.get().reevaluate();
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
        Instant now = clock.now();
        if (result != null) {
            TickPlan plan = schedule.next(start, now, result, firstUseAnchor());
            // A re-evaluation may only move the tick earlier (F12): otherwise 60 s heartbeats would postpone the
            // network tick for as long as an alarm rings. A tick overdue by more than the grace never ran → re-arm.
            boolean tickMissing = armedTickAt == null || armedTickAt.isBefore(now.minus(MISSED_TICK_GRACE));
            if (tickMissing || plan.at().isBefore(armedTickAt)) {
                armTick(plan);
            }
        } else if (armedTickAt == null) {
            armTick(schedule.afterFailure(now));
        }
        armWatchdog(now, threshold(result), null);
        updateHeartbeat(result);
        if (result != null) {
            lastResult = result;
        }
    }

    private void armTick(TickPlan plan) {
        if (LoopBreakSwitch.suppressed()) {   // VR-16 Run B, debug receiver only (Q-63)
            alarms.cancelTick();
            armedTickAt = null;
            plannedTickElapsed = null;
            return;
        }
        try {
            plannedTickElapsed = alarms.armTick(plan);
            armedTickAt = plan.at();
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
    }

    /**
     * Arms the watchdog from the stored {@code lastCheckCompleted} (F2). If the store cannot be read, the start of
     * a completed tick stands in for it; with neither, the stale re-arm ({@code now + 5 min}) keeps the net alive.
     */
    private void armWatchdog(Instant now, Duration threshold, Instant completedTickStart) {
        try {
            MonitorState ms = readMonitorState();
            Instant at;
            if (ms != null) {
                at = watchdogSchedule.armAt(now, anchor(ms), threshold, notRunningSnoozedUntil());
            } else if (completedTickStart != null) {
                at = watchdogSchedule.armAt(now, completedTickStart, threshold, notRunningSnoozedUntil());
            } else {
                at = watchdogSchedule.afterFire(now, notRunningSnoozedUntil());
            }
            if (at != null) {
                alarms.armWatchdog(at);
            }
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
    }

    private void updateHeartbeat(SyncResult result) {
        try {
            if (ringing(result)) {
                alarms.armHeartbeat();
            } else {
                alarms.cancelHeartbeat();
            }
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
    }

    private boolean ringing(SyncResult result) {
        if (result != null) {
            return result.ringing();
        }
        // Unknown (the evaluation failed): keep the ring alive if ENT-08 says one rings.
        try {
            return alarmState.get().activeType() != null;
        } catch (RuntimeException e) {
            return lastResult != null && lastResult.ringing();
        }
    }

    private void ensureAlarmChannelBypass() {
        try {
            channels.ensureAlarmChannelBypass();
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
    }

    private void ensureJobNet() {
        if (jobEnsured) {
            return;
        }
        try {
            jobNet.ensureScheduled();
            jobEnsured = true;
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
    }

    /**
     * F5 baseline (Q-59 / IR-76): monitoring just got configured in the current boot, so this boot needs no resume;
     * written without a log. Without it the first app start after install would log a false
     * {@code BOOT_NOT_HANDLED}.
     */
    private void recordBootBaseline() {
        bootChecked = true;
        try {
            long count = bootCount.bootCount();
            if (count >= 0) {
                flags.setHandledBootCount(count);
            }
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
    }

    /**
     * IR-76: the first completed tick of a process compares {@code BOOT_COUNT} with the handled count; a mismatch
     * means {@code BOOT_COMPLETED} never resumed monitoring in this boot: {@code BOOT_NOT_HANDLED} (exactly once per
     * boot, guard shared with {@link BootCountCheck}), then the stored count is healed.
     */
    private void healBootCount() {
        if (bootChecked) {
            return;
        }
        bootChecked = true;
        try {
            long count = bootCount.bootCount();
            if (count >= 0 && count != flags.handledBootCount()) {
                bootGuard.logOnce(count, logger);
                flags.setHandledBootCount(count);
            }
        } catch (RuntimeException e) {
            recordUnexpected(e);
        }
    }

    private void noteLateness(TickReason reason) {
        Long planned = plannedTickElapsed;
        plannedTickElapsed = null;
        if (reason != TickReason.SCHEDULED || planned == null) {
            return;
        }
        long late = clock.elapsedRealtimeMs() - planned;
        if (late > LATE_THRESHOLD_MS) {
            logger.event(EventCode.TICK_LATE, Long.toString(late));
        }
    }

    /** WF-05: monitoring not configured → no ticks; cancel tick, watchdog, heartbeat, boot retry and job; stop. */
    private void stopAll() {
        armedTickAt = null;
        plannedTickElapsed = null;
        jobEnsured = false;
        try {
            alarms.cancelAll();
            jobNet.cancel();
        } finally {
            launcher.stop();
        }
    }

    private boolean monitoringConfigured() {
        try {
            return flags.monitoringConfigured();
        } catch (RuntimeException e) {
            return true;   // fail-safe: never stop monitoring because a flag could not be read (SR-37)
        }
    }

    private MonitorState readMonitorState() {
        try {
            return monitorState.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** R-61 / Q-58: {@code monitoringSince} anchors the schedule while no reading exists. */
    private Instant firstUseAnchor() {
        MonitorState ms = readMonitorState();
        return ms != null ? ms.monitoringSince() : null;
    }

    private static Instant anchor(MonitorState ms) {
        return ms.lastCheckCompleted() != null ? ms.lastCheckCompleted() : ms.monitoringSince();
    }

    private Instant notRunningSnoozedUntil() {
        try {
            AlarmState state = alarmState.get();
            AlarmTypeState notRunning = state.perType().get(AlarmType.NOT_RUNNING);
            return notRunning != null ? notRunning.snoozedUntil() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Duration threshold(SyncResult result) {
        if (result != null) {
            return Duration.ofMinutes(result.noDataMinutes());
        }
        if (lastResult != null) {
            return Duration.ofMinutes(lastResult.noDataMinutes());
        }
        try {
            return settings.get().dataLossThreshold();
        } catch (RuntimeException e) {
            return Settings.defaults().dataLossThreshold();
        }
    }

    private void recordUnexpected(RuntimeException e) {
        logger.crash(e.getClass().getSimpleName(), topAppFrame(e));
    }

    private static String topAppFrame(Throwable e) {
        for (StackTraceElement frame : e.getStackTrace()) {
            if (frame.getClassName().startsWith("app.lightscout.")) {
                String cls = frame.getClassName();
                return cls.substring(cls.lastIndexOf('.') + 1) + "." + frame.getMethodName();
            }
        }
        return "unknown";
    }
}
