package app.lightscout.monitor;

import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Logger;
import app.lightscout.platform.NotificationChannels;

import java.util.concurrent.Executor;

/**
 * The direct-boot-safe subset of collaborators (CP-00 §6, 03 §6.5, R-66, F6) used by the {@code :monitor}
 * receivers, the job service and the first {@code startForeground}. Everything here must be usable **before the
 * first unlock** and cheap to obtain (no container build, no CE storage):
 *
 * <ul>
 *   <li>{@link #flags()} — the AppFlagsStore whose DE methods ({@code monitoringConfigured},
 *       {@code handledBootCount}) read ST-05; its CE methods are never called by {@code :monitor};</li>
 *   <li>{@link #channels()} — CMP-26 channel registry;</li>
 *   <li>{@link #logger()} — IF-66, a no-op for the CE ring buffer while locked, never throws (F6);</li>
 *   <li>{@link #clock()} — IF-15;</li>
 *   <li>{@link #receiverExecutor()} — a JDK background executor for receiver work after {@code goAsync()} (keeps DE
 *       prefs reads off the main thread, CP-00 §4). It must not be {@code monitorExecutor}: a long tick would
 *       hold a receiver past its broadcast timeout.</li>
 * </ul>
 */
public interface MonitorBootDependencies {

    /**
     * Returns the flag store (DE methods only are used).
     *
     * @return IF-60
     */
    AppFlagsStore flags();

    /**
     * Returns the channel registry.
     *
     * @return CMP-26
     */
    NotificationChannels channels();

    /**
     * Returns the logger (locked-safe).
     *
     * @return IF-66
     */
    Logger logger();

    /**
     * Returns the clock.
     *
     * @return IF-15
     */
    Clock clock();

    /**
     * Returns the background executor for receiver work.
     *
     * @return a JDK executor, safe while locked
     */
    Executor receiverExecutor();
}
