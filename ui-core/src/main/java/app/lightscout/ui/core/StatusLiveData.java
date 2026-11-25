package app.lightscout.ui.core;

import androidx.lifecycle.LiveData;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import app.lightscout.domain.port.StatusListener;
import app.lightscout.domain.status.StatusSnapshot;

/**
 * IF-18 → {@code LiveData} adapter (CP-00A R-12; 03 §4, ADR-024). Takes IF-21 {@code StatusRepository} as
 * functions so that {@code :ui-core} stays free of {@code :usecases}, e.g.
 * {@code StatusLiveData.of(repo::current, repo::addListener, repo::removeListener)}.
 *
 * <p>While the LiveData has active observers it is registered as a {@link StatusListener}; listener calls arrive
 * on {@code monitorExecutor} and are published with {@code postValue}. On activation the current snapshot (a
 * memory read, allowed on the main thread — R-34) is set immediately.
 */
public final class StatusLiveData {

    private StatusLiveData() {
    }

    /**
     * Creates the adapter.
     *
     * @param current supplies the current snapshot ({@code StatusRepository.current()})
     * @param add     registers a listener ({@code StatusRepository.addListener})
     * @param remove  unregisters a listener ({@code StatusRepository.removeListener})
     * @return a LiveData that follows the snapshot while observed
     */
    public static LiveData<StatusSnapshot> of(Supplier<StatusSnapshot> current, Consumer<StatusListener> add,
                                              Consumer<StatusListener> remove) {
        return new SnapshotLiveData(current, add, remove);
    }

    private static final class SnapshotLiveData extends LiveData<StatusSnapshot> implements StatusListener {

        private final Supplier<StatusSnapshot> current;
        private final Consumer<StatusListener> add;
        private final Consumer<StatusListener> remove;

        SnapshotLiveData(Supplier<StatusSnapshot> current, Consumer<StatusListener> add,
                         Consumer<StatusListener> remove) {
            this.current = Objects.requireNonNull(current, "current");
            this.add = Objects.requireNonNull(add, "add");
            this.remove = Objects.requireNonNull(remove, "remove");
        }

        @Override
        protected void onActive() {
            add.accept(this);
            StatusSnapshot now = current.get();
            if (now != null) {
                setValue(now);
            }
        }

        @Override
        protected void onInactive() {
            remove.accept(this);
        }

        @Override
        public void onStatus(StatusSnapshot s) {
            postValue(s);
        }
    }
}
