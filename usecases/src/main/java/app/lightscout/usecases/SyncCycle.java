package app.lightscout.usecases;

import app.lightscout.domain.status.TickReason;

/**
 * CMP-10 SyncCycle — IF-20 (CP-02 §8.2, CP-00A R-24).
 *
 * <p>Both methods run on {@code monitorExecutor} only; they are called by {@code :monitor}.
 */
public interface SyncCycle {

    /**
     * Runs one sync tick: WF-01 steps 2–6 (poll changes, store, classify outcome, project status, evaluate
     * alarms, reminders and pump alerts, persist monitor state) plus daily maintenance in the first tick after
     * 03:00 local.
     *
     * @param reason why the tick runs
     * @return what CP-08 needs to schedule the next tick
     */
    SyncResult runOnce(TickReason reason);

    /**
     * Re-evaluates without network: projects the snapshot, evaluates alarms (IF-01 → IF-16), checks reminders
     * and pump alerts. Target of IF-62 {@code reevaluateAlarms()} and of {@code AlarmActions.snooze}.
     *
     * @return the same scheduling data as {@link #runOnce(TickReason)}
     */
    SyncResult reevaluate();
}
