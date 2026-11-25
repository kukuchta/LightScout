package app.lightscout.feature.onboarding.disclaimer;

import app.lightscout.feature.onboarding.StateStore;
import app.lightscout.usecases.OnboardingState;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * S-05 screen logic, free of Android types so that it is JVM-tested (CP-14 §7.2, §9 TST-06 S-05).
 *
 * <ul>
 *   <li>A-S05-0: ticking C-S05-05 enables „Continue” and hides the helper; the tick survives rotation and process
 *       death (saved state, P5).</li>
 *   <li>A-S05-1: „Continue” calls IF-27 {@code acceptDisclaimer()} once (blocking, so on {@code ioExecutor} —
 *       CP-00A R-34), then asks to open S-06. Repeated taps while it runs are ignored (GIR-1).</li>
 *   <li>A-S05-2: nothing is stored unless „Continue” succeeds (back exits — handled by the navigation stack).</li>
 * </ul>
 * Main thread only, except the IF-27 call.
 */
public final class DisclaimerModel {

    /** Receives state changes and the „open S-06” request on the main thread. */
    public interface Listener {

        /**
         * The visible state changed.
         *
         * @param state the new state
         */
        void onState(DisclaimerUiState state);

        /** The acceptance was stored: open S-06 (mode onboarding). */
        void onAccepted();
    }

    static final String KEY_ACKNOWLEDGED = "s05_acknowledged";

    private final OnboardingState onboarding;
    private final StateStore store;
    private final Executor io;
    private final Executor main;
    private Listener listener;
    private boolean acknowledged;
    private boolean accepting;

    /**
     * Creates the model.
     *
     * @param onboarding IF-27
     * @param store      saved state (checkbox only)
     * @param io         {@code ioExecutor} for the blocking IF-27 call
     * @param main       main-thread executor for results
     */
    public DisclaimerModel(OnboardingState onboarding, StateStore store, Executor io, Executor main) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.store = Objects.requireNonNull(store, "store");
        this.io = Objects.requireNonNull(io, "io");
        this.main = Objects.requireNonNull(main, "main");
        this.acknowledged = store.getBoolean(KEY_ACKNOWLEDGED, false);
    }

    /**
     * Sets the listener and delivers the current state to it.
     *
     * @param l the listener, or {@code null} to detach
     */
    public void setListener(Listener l) {
        listener = l;
        publish();
    }

    /**
     * Returns the current state.
     *
     * @return the state
     */
    public DisclaimerUiState state() {
        return new DisclaimerUiState(acknowledged, acknowledged && !accepting, !acknowledged);
    }

    /**
     * A-S05-0: the checkbox changed.
     *
     * @param checked new checkbox state
     */
    public void setAcknowledged(boolean checked) {
        if (checked == acknowledged) {
            return;
        }
        acknowledged = checked;
        store.putBoolean(KEY_ACKNOWLEDGED, checked);
        publish();
    }

    /** A-S05-1: „Continue” tapped. Ignored when not ticked or while an acceptance is in flight (GIR-1). */
    public void onContinue() {
        if (!acknowledged || accepting) {
            return;
        }
        accepting = true;
        publish();
        io.execute(() -> {
            boolean stored;
            try {
                onboarding.acceptDisclaimer();
                stored = true;
            } catch (RuntimeException e) {
                // Store failure (StorageException, R-20): stay on S-05; the user can tap „Continue” again.
                stored = false;
            }
            final boolean ok = stored;
            main.execute(() -> onAcceptDone(ok));
        });
    }

    private void onAcceptDone(boolean ok) {
        accepting = false;
        publish();
        if (ok && listener != null) {
            listener.onAccepted();
        }
    }

    private void publish() {
        if (listener != null) {
            listener.onState(state());
        }
    }
}
