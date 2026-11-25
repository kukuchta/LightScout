package app.lightscout.monitor;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * Starts {@code MonitorService} for an origin and handles a refusal (06 §11 F4): event
 * {@code FGS_START_DENIED:<origin>} and the retry alarm of the F4 table (WF-04: boot / update → 5 s). Pure Java.
 */
final class ServiceLauncher {

    private final RuntimePorts.ServiceControl service;
    private final MonitorAlarms alarms;
    private final Logger logger;

    /**
     * Creates the launcher.
     *
     * @param service service seam
     * @param alarms  alarm scheduler (retry)
     * @param logger  IF-66
     */
    ServiceLauncher(RuntimePorts.ServiceControl service, MonitorAlarms alarms, Logger logger) {
        this.service = service;
        this.alarms = alarms;
        this.logger = logger;
    }

    /**
     * Starts the service.
     *
     * @param origin origin
     * @return {@code true} when the start was accepted
     */
    boolean launch(StartOrigin origin) {
        if (service.start(origin)) {
            return true;
        }
        logger.event(EventCode.FGS_START_DENIED, origin.name());
        switch (StartDispatch.retry(origin)) {
            case TICK -> alarms.armTickRetry(StartDispatch.retryDelay(origin));
            case HEARTBEAT -> alarms.armHeartbeat(StartDispatch.retryDelay(origin));
            case BOOT_RETRY -> alarms.armBootRetry(StartDispatch.retryDelay(origin));
            case NONE -> {
                // covered by another net: watchdog (re-armed before the start), JobScheduler, tick alarm
            }
        }
        return false;
    }

    /** Stops the service. */
    void stop() {
        service.stop();
    }
}
