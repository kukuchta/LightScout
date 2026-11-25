package app.lightscout.monitor;

/**
 * Which rule of the 06 §4 schedule (or F12) produced a tick plan.
 */
enum TickPhase {
    /** First tick of the arrival window, {@code E + lag₇₅ + 5 s}: the only tick the VR-07 {@code setAlarmClock}
     *  fallback may use (D-020, D-030). */
    EXPECTED_READING,
    /** 60 s window cadence. */
    ARRIVAL_WINDOW,
    /** 2 min overdue cadence. */
    OVERDUE,
    /** 5 min cadence after the data-loss threshold. */
    DATA_LOSS,
    /** No reading yet: 2 min cadence before {@code monitoringSince + threshold} (Q-58). */
    NO_READING,
    /** {@code AlarmDecision.nextEvaluation} was earlier than the schedule (F12). */
    EVALUATION,
    /** Retry after an unexpected tick failure or a refused service start. */
    RETRY
}
