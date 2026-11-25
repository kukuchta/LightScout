package app.lightscout.ui.core;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.fragment.NavHostFragment;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Result messages between destinations (CP-00A R-12, R-38).
 *
 * <p>{@link Navigator#finishWithResult(UiMessage)} (implemented in {@code :app}) puts the {@link UiMessage} into
 * the previous back-stack entry's {@link SavedStateHandle} under {@link #KEY} and pops. The receiving screen
 * (S-01, S-04) calls {@link #observe(Fragment, Consumer)} in {@code onViewCreated}; each message is delivered once
 * and then cleared.
 */
public final class NavResults {

    /** {@link SavedStateHandle} key under which the {@link UiMessage} is stored. */
    public static final String KEY = "app.lightscout.ui.core.NavResults.message";

    private NavResults() {
    }

    /**
     * Observes result messages for a fragment's own back-stack entry; each message is consumed (the handle value is
     * set to {@code null}) before {@code onMessage} runs. Call on the main thread after the fragment's view exists.
     *
     * <p>The fragment's <em>own</em> back-stack entry is used, found by its destination id: when the view is created
     * while another destination is on top (the activity was recreated with S-02 open), the current entry is that other
     * destination and the message written to this entry would never be observed (BUG-43).
     *
     * @param f             the receiving fragment (hosted by a {@code NavHostFragment})
     * @param destinationId the navigation destination id of {@code f}
     * @param onMessage     receives each message once, on the main thread
     */
    public static void observe(Fragment f, int destinationId, Consumer<UiMessage> onMessage) {
        Objects.requireNonNull(onMessage, "onMessage");
        NavBackStackEntry entry;
        try {
            entry = NavHostFragment.findNavController(f).getBackStackEntry(destinationId);
        } catch (IllegalArgumentException e) {
            return;   // the destination is not on the back stack (nothing to deliver to)
        }
        SavedStateHandle handle = entry.getSavedStateHandle();
        MutableLiveData<UiMessage> live = handle.getLiveData(KEY);
        LifecycleOwner owner = f.getView() != null ? f.getViewLifecycleOwner() : f;
        live.observe(owner, message -> {
            if (message != null) {
                // set(null), not remove(): remove() detaches this LiveData, so every later result would be lost
                handle.set(KEY, null);
                onMessage.accept(message);
            }
        });
    }
}
