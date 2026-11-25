package app.lightscout.monitor;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * JobScheduler safety net (ADR-045): one persisted periodic job (15 min, inexact; {@link AndroidJobNet}), not
 * exported, protected by {@code BIND_JOB_SERVICE} (SR-20). Each run: F5 boot-count check (log only), then — if
 * monitoring is configured and {@link MonitorService} is not running — start it (origin {@code JOB_NET}; no alarm).
 * A refused start is logged; the periodic job runs again and the tick alarm still runs (F4).
 */
public final class MonitorJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        MonitorBootDependencies boot = MonitorComponents.provider(this).monitorBootDependencies();
        boot.receiverExecutor().execute(() -> {
            try {
                new BootCountCheck(this, boot.flags(), boot.logger()).run();
                if (boot.flags().monitoringConfigured() && !MonitorService.isRunning()) {
                    MonitorComponents.launcher(this, boot).launch(StartOrigin.JOB_NET);
                }
            } catch (RuntimeException e) {
                boot.logger().crash(e.getClass().getSimpleName(), "MonitorJobService.onStartJob");
            } finally {
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;   // periodic: the next period runs anyway
    }
}
