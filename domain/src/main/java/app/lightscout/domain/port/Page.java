package app.lightscout.domain.port;

import java.util.List;
import java.util.Objects;

/**
 * One page of a bounded remote query (03 §6.2).
 *
 * <p>{@code complete = false} (the limit was reached) is <b>never</b> used for set-difference deletes (C-8).
 *
 * @param items    unmodifiable items of the page
 * @param complete {@code true} if the server returned fewer documents than the limit
 * @param <T>      item type
 */
public record Page<T>(List<T> items, boolean complete) {

    /**
     * Creates a page; the item list is copied unmodifiably.
     *
     * @throws NullPointerException if {@code items} or one of its elements is null
     */
    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
