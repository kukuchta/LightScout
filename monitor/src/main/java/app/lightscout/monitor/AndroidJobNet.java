package app.lightscout.monitor;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/**
 * The JobScheduler net (ADR-045): one persisted periodic job, 15 min, inexact, no constraints, that only restarts
 * {@link MonitorService} if it is not running (no alarm). Not WorkManager (CP-08 §12).
 */
final class AndroidJobNet implements RuntimePorts.JobNet {

    /** Job id (unique in the app; {@code :monitor} owns 8xx). */
    static final int JOB_ID = 801;
    /** Period: 15 min (the JobScheduler minimum). */
    static final long PERIOD_MS = 15L * 60L * 1000L;

    private final Context context;

    /**
     * Creates the adapter.
     *
     * @param context any context (the application context is kept)
     */
    AndroidJobNet(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void ensureScheduled() {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, MonitorJobService.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();
        scheduler.schedule(job);
    }

    @Override
    public void cancel() {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }
}
