package app.lightscout.feature.entry;

import java.util.Objects;

/**
 * A one-time event carried through {@code LiveData}: the first {@link #take()} returns the content, every later
 * call returns {@code null} (navigation must not repeat after a configuration change). Main thread only.
 *
 * @param <T> content type
 */
public final class OneShot<T> {

    private final T content;
    private boolean taken;

    /**
     * Wraps an event.
     *
     * @param content the event, non-null
     */
    public OneShot(T content) {
        this.content = Objects.requireNonNull(content, "content");
    }

    /**
     * Returns the content once.
     *
     * @return the content on the first call, {@code null} afterwards
     */
    public T take() {
        if (taken) {
            return null;
        }
        taken = true;
        return content;
    }
}
