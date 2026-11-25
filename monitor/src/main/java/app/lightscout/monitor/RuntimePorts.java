package app.lightscout.monitor;

/**
 * Small seams over Android services used by the JVM-testable orchestration ({@link TickRunner},
 * {@link ServiceLauncher}). Android adapters: {@link AndroidJobNet}, {@link AndroidServiceStarter},
 * {@link TickWakeLock}, {@link SettingsBootCountSource}.
 */
final class RuntimePorts {

    private RuntimePorts() {
    }

    /** The JobScheduler restart net (ADR-045): one persisted periodic job, 15 min, inexact. */
    interface JobNet {

        /** Schedules the job unless it is already pending (idempotent). */
        void ensureScheduled();

        /** Cancels the job. */
        void cancel();
    }

    /** Starts / stops {@code MonitorService}. */
    interface ServiceControl {

        /**
         * Calls {@code startForegroundService}.
         *
         * @param origin start origin (F4)
         * @return {@code false} when the platform refused the start ({@code ForegroundServiceStartNotAllowedException})
         */
        boolean start(StartOrigin origin);

        /** Stops the service. */
        void stop();
    }

    /**
     * The tick's own sync wake lock (WF-01 step 1, CP-00 §4; Q-65): timed, not reference counted, each acquisition
     * at most 30 s, at most 120 s per session ({@link WakeLockBudget}).
     */
    interface WakeLock {

        /** Starts a session: acquires for at most 30 s. */
        void acquire();

        /** Re-acquires for at most 30 s within the session cap (no-op when not held or the cap is spent). */
        void refresh();

        /** Ends the session: releases if held. */
        void release();
    }

    /** {@code Settings.Global.BOOT_COUNT} (F5). */
    interface BootCountSource {

        /**
         * Returns the current boot count.
         *
         * @return the count, or {@code -1} when unavailable
         */
        long bootCount();
    }
}
