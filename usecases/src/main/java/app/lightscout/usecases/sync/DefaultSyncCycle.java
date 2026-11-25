package app.lightscout.usecases.sync;

import app.lightscout.domain.alarm.AlarmCommand;
import app.lightscout.domain.alarm.AlarmDecision;
import app.lightscout.domain.alarm.AlarmEvaluator;
import app.lightscout.domain.alarm.AlarmInputs;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.WatchdogPolicy;
import app.lightscout.domain.dataloss.DataLossClassifier;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.entry.ReadingRules;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.SyncCycle;
import app.lightscout.usecases.SyncResult;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import app.lightscout.usecases.alarm.AlarmStateTransitions;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.status.DefaultStatusRepository;
import app.lightscout.usecases.status.ReadingQueries;
import app.lightscout.usecases.status.StatusProjector;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CMP-10 implementation of IF-20 (CP-02 §8.2, §8.10; 06 WF-01 steps 2–6; CP-00A R-24, R-35, R-67…R-70).
 *
 * <p>Runs on {@code monitorExecutor} only (single writer of IF-13 / IF-14 / IF-63 / IF-64, no locks). Every
 * {@code MonitorStateStore.put} follows a {@code get} in the same call. Nothing here stops a tick: network
 * failures become the tick outcome, {@link StorageException} becomes STORAGE_ERROR (R-20, SR-37).
 *
 * <p>Tick order: lastModified (steps 2–3) → changed collections (step 4) → alarms (IF-01 → persist ENT-08 →
 * IF-16) → reminders / pump alerts (step 5) → snapshot → monitor state (step 6) → <b>post-publish phase</b>: daily
 * maintenance / version re-check → SY-8 backfill → monitor state again if they changed it (and the snapshot again
 * if {@code connectionProblem} changed). The snapshot is projected after the alarm decision so that
 * {@code ringing} is current (assumption A-8).
 *
 * <p>Q-101 (BUG-18 S-B F3): alarm evaluation and publication never wait for reconcile or backfill traffic.
 * Maintenance / backfill never change the tick outcome ({@code SYNC_OK} / {@code SYNC_FAIL},
 * {@code lastSuccessfulCheck}, {@code lastCheckCompleted}) nor the alarm decision; a failure there (network or storage) is retried by their own
 * rules (next tick / next day).
 */
public final class DefaultSyncCycle implements SyncCycle {

    /**
     * Logcat tag of the debug-only per-phase tick timing line: {@code tick read=<ms>ms lastModified=<ms>ms
     * fetch=<ms>ms evaluate=<ms>ms publish=<ms>ms persist=<ms>ms maintenance=<ms>ms total=<ms>ms} (phase order,
     * Q-101). „lastModified” includes an IF-30 JWT fetch when one was due; „maintenance” = daily maintenance /
     * version re-check + SY-8 backfill + the second monitor-state write; the per-call split is logged by
     * {@code :nightscout}.
     */
    static final String TIMING_TAG = "SyncTiming";

    private final SyncPorts ports;
    private final StatusProjector projector;
    private final DefaultStatusRepository status;
    private final ChangeSync changeSync;
    private final DailyMaintenance maintenance;
    private final ReminderPlanner reminders;
    private final PumpAlertPlanner pumpAlerts;
    private final AlarmEvaluator evaluator = new AlarmEvaluator();
    private final DataLossClassifier dataLossClassifier = new DataLossClassifier();
    private final WatchdogPolicy watchdogPolicy = new WatchdogPolicy();

    private final AlarmStateMemory alarmMemory;

    private final MonitorStateMemory monitorMemory;
    private Settings lastKnownSettings;
    private boolean settingsReadFailed;
    private boolean evaluatedInProcess;
    /**
     * BUG-18 N4: when this process last ran the daily maintenance. Guards against a persistently failing
     * monitor-state write, which would otherwise leave {@code lastDailyMaintenance} unchanged and repeat the
     * maintenance (with its 31-day reconcile) on every tick. Only valid for the same {@code monitoringSince}: a
     * reset monitor state (new server / URL) runs the maintenance at once.
     */
    private Instant maintenanceRanInProcess;
    private Instant maintenanceRanForMonitoringSince;

    /**
     * Creates the cycle.
     *
     * @param ports     the ports
     * @param projector the status projector
     * @param status      the status repository that receives every snapshot
     * @param alarmMemory   in-memory ENT-08 shared with {@code AlarmActions} and the projector
     * @param monitorMemory last authoritative monitor state, shared with the projector
     */
    public DefaultSyncCycle(SyncPorts ports, StatusProjector projector, DefaultStatusRepository status,
                            AlarmStateMemory alarmMemory, MonitorStateMemory monitorMemory) {
        this.ports = ports;
        this.alarmMemory = alarmMemory;
        this.monitorMemory = monitorMemory;
        this.projector = projector;
        this.status = status;
        this.changeSync = new ChangeSync(ports.gateway(), ports.readings(), ports.entries(), ports.deviceStatus(),
                ports.logger());
        this.maintenance = new DailyMaintenance(ports.gateway(), ports.connectionStore(), ports.readings(),
                ports.entries(), ports.deviceStatus(), ports.notifiedAlerts(), changeSync, ports.logger());
        this.reminders = new ReminderPlanner(ports.entries(), ports.reminderState(), ports.reminderScheduler());
        this.pumpAlerts = new PumpAlertPlanner(ports.entries(), ports.notifiedAlerts(), ports.pumpAlertNotifier());
    }

    @Override
    public SyncResult runOnce(TickReason reason) {
        status.markFetching(true);
        try {
            return tick();
        } finally {
            status.markFetching(false);   // IR-41: always published, also if the tick throws (GIR-1, SR-37)
        }
    }

    private SyncResult tick() {
        long t0 = elapsedMs();
        Instant now = ports.clock().now();
        // A failed get falls back to the in-memory state of this process (authoritative: single writer), so
        // lastCheckCompleted is still persisted (04 §8, F2). An initial() fallback is never written.
        boolean authoritativeBase = true;
        boolean readFailed = false;
        MonitorState prev;
        try {
            prev = ports.monitorState().get();
        } catch (StorageException e) {
            readFailed = true;
            authoritativeBase = monitorMemory.get() != null;
            prev = authoritativeBase ? monitorMemory.get() : MonitorState.initial();
        }
        Settings settings = readSettings();
        TickContext t = new TickContext(now, prev, settings);
        if (readFailed || settingsReadFailed) {
            t.storageFailed = true;   // R-20: IF-13 / IF-11 failure → STORAGE_ERROR
        }

        long tRead = elapsedMs();

        // Steps 2–4: JWT (inside IF-07), lastModified, changed collections.
        Result<Map<NsCollection, Long>> lm = ports.gateway().lastModified();
        long tLastModified = elapsedMs();
        if (!lm.ok()) {
            t.fail(lm.outcome(), lm.detailCode());
        } else {
            changeSync.fetchChanged(t, lm.value() == null ? Map.of() : lm.value());
        }
        long tFetch = elapsedMs();
        FetchOutcome outcome = t.outcome();
        String detail = t.detail();
        final FetchOutcome fetchOutcome = outcome;              // steps 2–4, before step 5 / 6 may add STORAGE_ERROR
        final boolean postPhase = lm.ok() && !t.networkFailed();

        // M-13 lastReadingTime and the arrival lag (R-69).
        Instant prevLast = prev.lastReadingTime();
        Instant newLast = prevLast;
        List<Integer> samples = new ArrayList<>();
        for (Reading r : t.fetchedReadings) {
            if (r == null || r.time() == null || ReadingRules.isFutureDated(r, now)) {
                continue;
            }
            if (prevLast != null && r.time().isAfter(prevLast)) {
                // D-152: how long the server needed, not how long we took to look (that is censored by the schedule).
                Long srv = t.srvModifiedByReading.get(r.id());
                if (srv != null) {
                    LagEstimator.sample(r.time().toEpochMilli(), srv).ifPresent(samples::add);
                }
            }
            if (newLast == null || r.time().isAfter(newLast)) {
                newLast = r.time();
            }
        }
        boolean newReading = newLast != null && (prevLast == null || newLast.isAfter(prevLast));
        List<Integer> recentLag = LagEstimator.addSamples(prev.recentLagSec(), samples);
        int lagMedian = samples.isEmpty() ? prev.arrivalLagMedianSec()
                : LagEstimator.median(recentLag, prev.arrivalLagMedianSec());

        // Step 6 values, computed now so that step 5 projects the post-tick state (R-69 (1)).
        MonitorState next = new MonitorState(
                prev.monitoringSince() != null ? prev.monitoringSince() : now,
                outcome == FetchOutcome.OK ? now : prev.lastSuccessfulCheck(),
                now, outcome, t.connectionProblem, newLast, lagMedian, recentLag,
                t.lastDailyMaintenance, t.cursorsOut());

        // Step 5. Watchdog: lastCheckCompleted as read before this tick writes it, else monitoringSince (R-68, Q-26).
        Instant watchdogAnchor = prev.lastCheckCompleted() != null ? prev.lastCheckCompleted() : prev.monitoringSince();
        boolean stale = staleWatchdog(now, watchdogAnchor, settings);
        Evaluation ev = evaluate(now, next, settings, t.fetchedReadings, stale, watchdogAnchor);
        long tEvaluate = elapsedMs();
        if (ev.storageFailed() && outcome == FetchOutcome.OK) {
            // IR-40 / R-20: IF-08 / IF-14 / IF-63 / IF-64 failure in step 5 → STORAGE_ERROR; steps 2–4 were OK,
            // so lastSuccessfulCheck stays this tick's
            outcome = FetchOutcome.STORAGE_ERROR;
            detail = app.lightscout.domain.port.DetailCode.STORAGE;
            next = new MonitorState(next.monitoringSince(), next.lastSuccessfulCheck(), next.lastCheckCompleted(),
                    outcome, next.connectionProblem(), next.lastReadingTime(), next.arrivalLagMedianSec(),
                    next.recentLagSec(), next.lastDailyMaintenance(), next.cursors());
        }
        publish(now, next, settings, ev);
        long tPublish = elapsedMs();

        // Step 6: persist monitor state (lastSuccessfulCheck only if steps 2–4 OK; the rest always).
        if (authoritativeBase) {
            try {
                ports.monitorState().put(next);
            } catch (StorageException e) {
                if (outcome == FetchOutcome.OK) {
                    outcome = FetchOutcome.STORAGE_ERROR;
                    detail = app.lightscout.domain.port.DetailCode.STORAGE;
                }
            }
            monitorMemory.set(next);
        }
        long tPersist = elapsedMs();

        // Post-publish phase (Q-101): daily maintenance / version re-check, SY-8 backfill.
        if (postPhase) {
            maintainAndBackfill(t, now, prev, fetchOutcome, next, settings, ev, authoritativeBase);
        }
        long tMaintenance = elapsedMs();
        // Debug builds only (Logger.debug: dropped in release, stripped by R8): durations, never values (SR-07).
        ports.logger().debug(TIMING_TAG, "tick read=" + (tRead - t0) + "ms lastModified=" + (tLastModified - tRead)
                + "ms fetch=" + (tFetch - tLastModified) + "ms evaluate=" + (tEvaluate - tFetch)
                + "ms publish=" + (tPublish - tEvaluate) + "ms persist=" + (tPersist - tPublish)
                + "ms maintenance=" + (tMaintenance - tPersist) + "ms total=" + (tMaintenance - t0) + "ms");
        logOutcome(outcome, detail);
        return new SyncResult(outcome, newReading, newLast, ev.decision().nextEvaluation(),
                ev.state().activeType() != null, lagMedian, settings.noDataMinutes());
    }

    /**
     * Q-101 post-publish phase: WF-12 daily maintenance (or the sticky version re-check) and the SY-8 backfill
     * (OK ticks only), then a second monitor-state write with their cursor / {@code connectionProblem} /
     * {@code lastDailyMaintenance} changes. The tick outcome, {@code lastCheckCompleted} and the alarm decision
     * are final before this runs; a failed write here is ignored (maintenance runs again next tick, the backfill
     * slice is fetched again). The snapshot is re-published only when {@code connectionProblem} changed.
     */
    private void maintainAndBackfill(TickContext t, Instant now, MonitorState prev, FetchOutcome fetchOutcome,
                                     MonitorState published, Settings settings, Evaluation ev,
                                     boolean authoritativeBase) {
        Instant lastDaily = prev.lastDailyMaintenance();
        if (maintenanceRanInProcess != null && (lastDaily == null || maintenanceRanInProcess.isAfter(lastDaily))
                && prev.monitoringSince() != null
                && prev.monitoringSince().equals(maintenanceRanForMonitoringSince)) {
            lastDaily = maintenanceRanInProcess;
        }
        if (DailyMaintenance.isDue(now, ports.clock().zone(), lastDaily)) {
            maintenanceRanInProcess = now;
            maintenanceRanForMonitoringSince = prev.monitoringSince();
            maintenance.run(t);
        } else if (t.connectionProblem == FetchOutcome.SERVER_TOO_OLD) {
            maintenance.recheckVersion(t);
        }
        if (fetchOutcome == FetchOutcome.OK) {
            changeSync.backfill(t, this::elapsedMs);
        }
        MonitorState after = new MonitorState(published.monitoringSince(), published.lastSuccessfulCheck(),
                published.lastCheckCompleted(), published.lastOutcome(), t.connectionProblem,
                published.lastReadingTime(), published.arrivalLagMedianSec(), published.recentLagSec(),
                t.lastDailyMaintenance, t.cursorsOut());
        if (after.equals(published)) {
            return;
        }
        if (authoritativeBase) {
            try {
                ports.monitorState().put(after);
            } catch (StorageException e) {
                // ignored: the tick outcome is final (Q-101); the store already logged STORAGE_ERROR (R-13)
            }
            monitorMemory.set(after);
        }
        if (after.connectionProblem() != published.connectionProblem()) {
            publish(now, after, settings, ev);
        }
    }

    /**
     * IF-03 with the DCR-36 / D-153 exception: shortly after a system start a stale anchor from before the boot is the
     * phone having been off, not a stopped loop (any evaluation in the window, tick or re-evaluation; logged).
     */
    private boolean staleWatchdog(Instant now, Instant watchdogAnchor, Settings settings) {
        if (!watchdogPolicy.isNotRunning(now, watchdogAnchor, settings.dataLossThreshold())) {
            return false;
        }
        if (watchdogPolicy.explainedByPowerOff(watchdogAnchor, now,
                Duration.ofMillis(ports.clock().elapsedRealtimeMs()))) {
            ports.logger().event(EventCode.WATCHDOG_SKIPPED_BOOT);
            return false;
        }
        return true;
    }

    @Override
    public SyncResult reevaluate() {
        Instant now = ports.clock().now();
        MonitorState ms;
        try {
            ms = ports.monitorState().get();
            monitorMemory.set(ms);
        } catch (StorageException e) {
            ms = monitorMemory.get() != null ? monitorMemory.get() : MonitorState.initial();
        }
        Settings settings = readSettings();
        Instant watchdogAnchor = ms.lastCheckCompleted() != null ? ms.lastCheckCompleted() : ms.monitoringSince();
        boolean stale = staleWatchdog(now, watchdogAnchor, settings);
        Evaluation ev = evaluate(now, ms, settings, List.of(), stale, watchdogAnchor);
        publish(now, ms, settings, ev);
        return new SyncResult(ms.lastOutcome(), false, ms.lastReadingTime(), ev.decision().nextEvaluation(),
                ev.state().activeType() != null, ms.arrivalLagMedianSec(), settings.noDataMinutes());
    }

    /** Alarm decision, the state it produced, the inputs the snapshot needs and whether a store call failed. */
    private record Evaluation(AlarmDecision decision, AlarmState state, Reading latest, DataLossStatus dataLoss,
                              boolean storageFailed) {
    }

    /** Projects and publishes the snapshot of this evaluation (ADR-025: same latest / data loss as the alarm view). */
    private void publish(Instant now, MonitorState ms, Settings settings, Evaluation ev) {
        status.publish(projector.project(now, ms, settings, ev.state(), ev.latest(), ev.dataLoss(), false));
    }

    /**
     * Step 5: IF-01 → persist ENT-08 (before apply, AL-9) → IF-16 apply (once, possibly empty) → reminders and pump
     * alerts (they continue while „Alarms” is off — D-022). The caller then publishes the snapshot.
     */
    private Evaluation evaluate(Instant now, MonitorState ms, Settings settings, List<Reading> fetched,
                                boolean watchdogStale, Instant lastCheckForView) {
        DataLossStatus dataLoss = dataLossClassifier.classify(now, ms.lastReadingTime(), ms.monitoringSince(),
                ms.lastOutcome(), settings.dataLossThreshold());
        boolean storageFailed = false;
        Reading latest;
        Reading futureLow;
        try {
            latest = ReadingQueries.latest(ports.readings(), now, fetched);
        } catch (StorageException e) {
            latest = ReadingRules.latest(fetched, now).orElse(null);
            storageFailed = true;
        }
        try {
            futureLow = ReadingQueries.futureDatedLow(ports.readings(), now, settings.lowMgdl(), fetched);
        } catch (StorageException e) {
            // IR-37: a future-dated low fetched in this tick still reaches the evaluator (M-9 amended, SR-29)
            futureLow = ReadingQueries.futureDatedLow(fetched, now, settings.lowMgdl());
            storageFailed = true;
        }

        alarmMemory.takeFailure();   // count only this evaluation's IF-14 failures
        AlarmState state = alarmMemory.read(ports.alarmState());
        boolean alarmStateKnown = alarmMemory.known();
        AlarmDecision decision = evaluator.evaluate(new AlarmInputs(now, latest, futureLow, settings, dataLoss,
                watchdogStale, lastCheckForView, state));
        List<AlarmCommand> commands = resumeOnFirstEvaluation(decision.commands(), state);
        AlarmState newState = AlarmStateTransitions.afterCommands(state, commands, now);
        if (alarmStateKnown && (!newState.equals(state) || alarmMemory.dirty())) {
            // a failed put keeps the state in memory (dirty); the ring itself still happens (D-003)
            alarmMemory.write(ports.alarmState(), newState);
        }
        try {
            // ClearSnooze is a state change only (persisted above); IF-16 receives the ringing commands
            ports.alarmController().apply(commands.stream()
                    .filter(c -> !(c instanceof AlarmCommand.ClearSnooze)).toList());
        } catch (RuntimeException e) {
            // IF-16 failures must not stop the tick (SR-37); the next evaluation re-sends the commands
        }

        storageFailed |= reminders.run(now, settings);
        storageFailed |= pumpAlerts.run(now);
        storageFailed |= alarmMemory.takeFailure();
        return new Evaluation(new AlarmDecision(commands, decision.nextEvaluation()), newState, latest, dataLoss,
                storageFailed);
    }

    /**
     * R-35: on the first evaluation in a process with a persisted {@code activeType}, the keep-alive
     * {@code Update} for it becomes {@code Start} (resume after process death / restart).
     */
    private List<AlarmCommand> resumeOnFirstEvaluation(List<AlarmCommand> commands, AlarmState state) {
        if (evaluatedInProcess) {
            return commands;
        }
        evaluatedInProcess = true;
        if (state.activeType() == null) {
            return commands;
        }
        List<AlarmCommand> out = new ArrayList<>(commands.size());
        for (AlarmCommand c : commands) {
            if (c instanceof AlarmCommand.Update u && u.view().type() == state.activeType()) {
                out.add(new AlarmCommand.Start(state.activeType(), u.view()));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    private Settings readSettings() {
        settingsReadFailed = false;
        try {
            lastKnownSettings = ports.settings().get();
        } catch (StorageException e) {
            settingsReadFailed = true;
            if (lastKnownSettings == null) {
                lastKnownSettings = Settings.defaults();
            }
        }
        return lastKnownSettings;
    }

    private long elapsedMs() {
        return ports.clock().elapsedRealtimeMs();
    }

    private void logOutcome(FetchOutcome outcome, String detail) {
        if (outcome == FetchOutcome.OK) {
            ports.logger().event(EventCode.SYNC_OK);
        } else {
            ports.logger().event(EventCode.SYNC_FAIL, detail == null ? outcome.name() : outcome.name() + ":" + detail);
        }
    }
}
