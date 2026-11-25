package app.lightscout.usecases.status;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.StatusRepository;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * CMP-11 implementation of IF-21 (ADR-025; CP-00A R-25). Holds the current snapshot in memory; every
 * projection runs on {@code monitorExecutor} and every change is delivered to all listeners there (WF-09).
 *
 * <p>Projection triggers: end of every tick / re-evaluation ({@link #publish}), {@link #refresh()}, IF-19
 * connectivity change and IF-11 settings change (both registered in the constructor).
 */
public final class DefaultStatusRepository implements StatusRepository {

    private final StatusProjector projector;
    private final Executor monitorExecutor;
    private final MonitorControl monitorControl;
    private final List<StatusListener> listeners = new CopyOnWriteArrayList<>();
    private volatile StatusSnapshot current;

    /**
     * Creates the repository and registers the IF-19 / IF-11 projection triggers.
     *
     * @param projector       the projector
     * @param connectivity    IF-19 (change → refresh)
     * @param settingsStore   IF-11 (change → refresh)
     * @param monitorExecutor the single monitor thread
     * @param monitorControl  IF-62 (retry)
     * @param clock           IF-15 (initial snapshot time)
     */
    public DefaultStatusRepository(StatusProjector projector, Connectivity connectivity, SettingsStore settingsStore,
                                   Executor monitorExecutor, MonitorControl monitorControl, Clock clock) {
        this.projector = Objects.requireNonNull(projector, "projector");
        this.monitorExecutor = Objects.requireNonNull(monitorExecutor, "monitorExecutor");
        this.monitorControl = Objects.requireNonNull(monitorControl, "monitorControl");
        this.current = StatusProjector.initial(clock.now());
        connectivity.addListener(online -> refresh());
        settingsStore.addListener(this::refresh);
    }

    @Override
    public StatusSnapshot current() {
        return current;
    }

    @Override
    public void addListener(StatusListener l) {
        listeners.add(Objects.requireNonNull(l, "listener"));
    }

    @Override
    public void removeListener(StatusListener l) {
        listeners.remove(l);
    }

    @Override
    public void refresh() {
        monitorExecutor.execute(() -> publish(projector.projectFromStores(current.fetching())));
    }

    @Override
    public void retry() {
        monitorControl.requestTick(TickReason.USER_RETRY);
    }

    /**
     * Stores a new snapshot and notifies every listener with the same object (ADR-025). Called on
     * {@code monitorExecutor} only (by the sync cycle and {@link #refresh()}). A failing listener never stops
     * the others or the tick (SR-37).
     *
     * @param snapshot the new snapshot
     */
    public void publish(StatusSnapshot snapshot) {
        StatusSnapshot previous = current;
        current = Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.equals(previous)) {
            return;
        }
        for (StatusListener l : listeners) {
            try {
                l.onStatus(snapshot);
            } catch (RuntimeException e) {
                // a listener failure must not stop the projection or the tick (SR-37)
            }
        }
    }

    /**
     * Publishes the current snapshot with another {@code fetching} flag (tick start / end). Monitor thread only.
     *
     * @param fetching a tick is running
     */
    public void markFetching(boolean fetching) {
        if (current.fetching() != fetching) {
            publish(StatusProjector.withFetching(current, fetching));
        }
    }
}
