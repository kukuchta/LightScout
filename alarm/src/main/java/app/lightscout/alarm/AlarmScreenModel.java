package app.lightscout.alarm;

import app.lightscout.domain.alarm.AlarmView;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Content source of S-09 (CP-00A R-80: S-09 renders the latest {@code AlarmView} from IF-16 {@code Start} /
 * {@code Replace} / {@code Update}). One per process, owned by {@link AlarmRuntime}. Thread-safe: written on
 * {@code monitorExecutor}, read by {@link AlarmActivity} on the main thread; listeners are called on the writing
 * thread and must hop to the main thread themselves.
 */
public final class AlarmScreenModel {

    /**
     * Receives content changes.
     */
    public interface Listener {

        /**
         * The content changed.
         *
         * @param view the latest view, or {@code null} when the alarm stopped (S-09 closes)
         */
        void onAlarmView(AlarmView view);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile AlarmView current;
    private volatile boolean foreground;

    AlarmScreenModel() {
    }

    /**
     * Returns the latest view of the ringing alarm.
     *
     * @return the view, or {@code null} when nothing rings in this process
     */
    public AlarmView current() {
        return current;
    }

    /**
     * Registers a listener.
     *
     * @param l the listener
     */
    public void addListener(Listener l) {
        listeners.add(l);
    }

    /**
     * Removes a listener.
     *
     * @param l the listener
     */
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /**
     * Tells whether S-09 is in the foreground (resumed); F11 re-posts CH-01 on {@code SCREEN_OFF} only otherwise.
     *
     * @return {@code true} while S-09 is resumed
     */
    public boolean foreground() {
        return foreground;
    }

    /**
     * Set by {@link AlarmActivity} in {@code onResume} / {@code onPause}.
     *
     * @param resumed {@code true} while resumed
     */
    void setForeground(boolean resumed) {
        foreground = resumed;
    }

    /**
     * Publishes a view.
     *
     * @param view the view, {@code null} to close S-09
     */
    void publish(AlarmView view) {
        current = view;
        for (Listener l : listeners) {
            l.onAlarmView(view);
        }
    }
}
