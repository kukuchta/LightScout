package app.lightscout.ui.core;

import java.util.function.Supplier;

/**
 * "Never throw" wrapper for surface rendering (06 §11 F4: render failure gives the resource icon).
 */
final class NeverThrow {

    private NeverThrow() {
    }

    /**
     * Returns the primary result, or the fallback if the primary throws or returns {@code null}.
     * {@link OutOfMemoryError} from bitmap allocation counts as a failure.
     *
     * @param primary  the preferred computation
     * @param fallback the safe computation
     * @param <T>      result type
     * @return the primary or the fallback result
     */
    static <T> T orElse(Supplier<T> primary, Supplier<T> fallback) {
        try {
            T value = primary.get();
            if (value != null) {
                return value;
            }
        } catch (RuntimeException | OutOfMemoryError e) {
            // Rendering failed; the fallback below is the defined behaviour (F4), nothing to report.
        }
        return fallback.get();
    }
}
