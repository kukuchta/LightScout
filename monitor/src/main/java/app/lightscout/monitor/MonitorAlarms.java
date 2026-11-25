package app.lightscout.monitor;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

import java.time.Duration;
import java.time.Instant;

/**
 * Arms the {@code :monitor} alarms (WF-01 step 6, WF-03, R-35, WF-04). Pure Java over {@link AlarmPort}.
 *
 * <ul>
 *   <li>Ticks, watchdog, heartbeat and boot retry are exact allow-while-idle alarms on
 *       {@code ELAPSED_REALTIME_WAKEUP} (ADR-043, F14): wall instants are converted with
 *       {@code elapsedNow + (at − now)}, so a clock jump cannot move an armed alarm.</li>
 *   <li>Exact refused (no permission or {@code SecurityException}) → inexact {@code setAndAllowWhileIdle} +
 *       {@code EXACT_ALARM_DENIED} (logged on the transition only); readiness item 5 raises the banner.</li>
 *   <li>{@code setAlarmClock} only for the {@link TickPhase#EXPECTED_READING} tick and only when the VR-07
 *       fallback switch {@link MonitorConfig#ALARM_CLOCK_FOR_EXPECTED_READING_TICK} is on (D-020, D-030).</li>
 * </ul>
 */
final class MonitorAlarms {

    /** Heartbeat interval while any alarm rings (ADR-067, R-35). */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);

    private final AlarmPort port;
    private final Clock clock;
    private final Logger logger;
    private final boolean alarmClockFallback;
    private boolean exactDenied;

    /**
     * Creates the scheduler.
     *
     * @param port               AlarmManager seam
     * @param clock              IF-15
     * @param logger             IF-66
     * @param alarmClockFallback VR-07 fallback switch
     */
    MonitorAlarms(AlarmPort port, Clock clock, Logger logger, boolean alarmClockFallback) {
        this.port = port;
        this.clock = clock;
        this.logger = logger;
        this.alarmClockFallback = alarmClockFallback;
    }

    /**
     * Arms the next tick.
     *
     * @param plan the plan
     * @return the elapsed-realtime trigger that was armed
     */
    long armTick(TickPlan plan) {
        long elapsed = toElapsed(plan.at());
        if (alarmClockFallback && plan.phase() == TickPhase.EXPECTED_READING) {
            try {
                port.setAlarmClock(AlarmPort.Kind.TICK, plan.at().toEpochMilli());
                return elapsed;
            } catch (SecurityException e) {
                // falls through to the regular path, which records the refusal
            }
        }
        armExact(AlarmPort.Kind.TICK, elapsed);
        return elapsed;
    }

    /**
     * Arms the tick alarm at {@code now + delay} (retry after a refused service start).
     *
     * @param delay delay
     */
    void armTickRetry(Duration delay) {
        armExact(AlarmPort.Kind.TICK, clock.elapsedRealtimeMs() + delay.toMillis());
    }

    /**
     * Arms the watchdog.
     *
     * @param at wall instant
     */
    void armWatchdog(Instant at) {
        armExact(AlarmPort.Kind.WATCHDOG, toElapsed(at));
    }

    /** Arms the heartbeat 60 s from now. */
    void armHeartbeat() {
        armExact(AlarmPort.Kind.HEARTBEAT, clock.elapsedRealtimeMs() + HEARTBEAT_INTERVAL.toMillis());
    }

    /**
     * Arms the heartbeat at {@code now + delay}.
     *
     * @param delay delay
     */
    void armHeartbeat(Duration delay) {
        armExact(AlarmPort.Kind.HEARTBEAT, clock.elapsedRealtimeMs() + delay.toMillis());
    }

    /** Cancels the tick alarm (VR-16 loop break only). */
    void cancelTick() {
        port.cancel(AlarmPort.Kind.TICK);
    }

    /** Cancels the heartbeat. */
    void cancelHeartbeat() {
        port.cancel(AlarmPort.Kind.HEARTBEAT);
    }

    /**
     * Arms the WF-04 boot-retry alarm.
     *
     * @param delay delay
     */
    void armBootRetry(Duration delay) {
        armExact(AlarmPort.Kind.BOOT_RETRY, clock.elapsedRealtimeMs() + delay.toMillis());
    }

    /** Cancels all four alarms (WF-05 monitoring not configured). */
    void cancelAll() {
        for (AlarmPort.Kind kind : AlarmPort.Kind.values()) {
            port.cancel(kind);
        }
    }

    /**
     * Converts a wall instant to an elapsed-realtime trigger; instants in the past trigger now.
     *
     * @param at wall instant
     * @return elapsed realtime ms
     */
    long toElapsed(Instant at) {
        long delta = Duration.between(clock.now(), at).toMillis();
        return clock.elapsedRealtimeMs() + Math.max(0L, delta);
    }

    private void armExact(AlarmPort.Kind kind, long elapsed) {
        if (port.canScheduleExact()) {
            try {
                port.setExactElapsed(kind, elapsed);
                exactDenied = false;
                return;
            } catch (SecurityException e) {
                // refused despite the check: inexact fallback below
            }
        }
        if (!exactDenied) {
            exactDenied = true;
            logger.event(EventCode.EXACT_ALARM_DENIED);
        }
        port.setInexactElapsed(kind, elapsed);
    }
}
