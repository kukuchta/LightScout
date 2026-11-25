package app.lightscout.monitor;

import app.lightscout.domain.status.TickReason;

import java.time.Duration;

/**
 * Pure decisions per {@link StartOrigin}: what the started service does, and how a refused start is retried
 * (06 §11 F4 start-origin table; WF-04 step 2).
 */
final class StartDispatch {

    /** WF-04: boot / update start refused → exact alarm in 5 s. */
    static final Duration BOOT_RETRY_DELAY = Duration.ofSeconds(5);
    /** F4: refused start from a tick / heartbeat / boot-retry alarm → retry at the next alarm, 60 s later. */
    static final Duration ALARM_RETRY_DELAY = Duration.ofSeconds(60);

    /** Work the service performs for a start. */
    enum Work {
        /** Nothing: the caller requests the tick itself (R-32 {@code startMonitoring}). */
        NONE,
        /** One sync tick with {@link #reason(StartOrigin)}. */
        TICK,
        /** Watchdog check (WF-03 steps 2–4). */
        WATCHDOG,
        /** Ringing heartbeat: re-evaluate without network (R-35). */
        HEARTBEAT
    }

    /** Which alarm carries the retry of a refused start. */
    enum Retry {
        /** No own retry: another net covers it (watchdog already re-armed, job reschedules, watchdog). */
        NONE,
        /** Re-arm the tick alarm. */
        TICK,
        /** Re-arm the heartbeat alarm. */
        HEARTBEAT,
        /** Arm the WF-04 boot-retry alarm. */
        BOOT_RETRY
    }

    private StartDispatch() {
    }

    /**
     * Returns the work for a start origin.
     *
     * @param origin origin
     * @return work
     */
    static Work work(StartOrigin origin) {
        return switch (origin) {
            case ACTIVITY -> Work.NONE;
            case WATCHDOG -> Work.WATCHDOG;
            case HEARTBEAT -> Work.HEARTBEAT;
            default -> Work.TICK;
        };
    }

    /**
     * Returns the tick reason for an origin (CP-00A R-10).
     *
     * @param origin origin
     * @return reason
     */
    static TickReason reason(StartOrigin origin) {
        return switch (origin) {
            case BOOT, BOOT_RETRY -> TickReason.BOOT;
            case PACKAGE_REPLACED -> TickReason.PACKAGE_REPLACED;
            case JOB_NET -> TickReason.JOB_NET;
            case STICKY_RESTART -> TickReason.SERVICE_RESTART;
            case TIME_CHANGED -> TickReason.TIME_CHANGED;
            case WATCHDOG -> TickReason.WATCHDOG;
            case HEARTBEAT -> TickReason.HEARTBEAT;
            default -> TickReason.SCHEDULED;
        };
    }

    /**
     * Tells whether a start from this origin resumes monitoring after a restart ({@code BOOT_RESUMED}).
     *
     * @param origin origin
     * @return {@code true} for boot and boot retry
     */
    static boolean resumesAfterBoot(StartOrigin origin) {
        return origin == StartOrigin.BOOT || origin == StartOrigin.BOOT_RETRY;
    }

    /**
     * Returns the retry alarm for a refused start (F4 „On refusal” column).
     *
     * @param origin origin
     * @return retry kind
     */
    static Retry retry(StartOrigin origin) {
        return switch (origin) {
            case TICK -> Retry.TICK;
            case HEARTBEAT -> Retry.HEARTBEAT;
            case BOOT, PACKAGE_REPLACED, BOOT_RETRY -> Retry.BOOT_RETRY;
            default -> Retry.NONE;
        };
    }

    /**
     * Retry alarm after {@code startForeground} itself was refused inside the service (🏛️ Q-66 / IR-72): the
     * heartbeat while an alarm rings (or the start came from it), otherwise the tick, both in 60 s. The service then
     * stops itself and never continues as a non-foreground service.
     *
     * @param origin  origin of the refused start
     * @param ringing last known {@code SyncResult.ringing} of this process
     * @return {@link Retry#HEARTBEAT} or {@link Retry#TICK}
     */
    static Retry afterForegroundRefusal(StartOrigin origin, boolean ringing) {
        return origin == StartOrigin.HEARTBEAT || ringing ? Retry.HEARTBEAT : Retry.TICK;
    }

    /**
     * Returns the retry delay for a refused start.
     *
     * @param origin origin
     * @return 5 s for the first boot / update start (WF-04), else 60 s
     */
    static Duration retryDelay(StartOrigin origin) {
        return origin == StartOrigin.BOOT || origin == StartOrigin.PACKAGE_REPLACED
                ? BOOT_RETRY_DELAY : ALARM_RETRY_DELAY;
    }
}
