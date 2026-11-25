package app.lightscout.nightscout;

import java.util.List;

/**
 * Output of mapping one response array (M-8, SR-29).
 *
 * @param items    mapped domain objects (unmodifiable)
 * @param rawCount number of documents the server returned (basis of {@code Page.complete}, C-8)
 * @param skipped  number of invalid documents skipped (logged as {@code MAPPING_SKIPPED}, never aborting)
 * @param <T>      item type
 */
record Mapped<T>(List<T> items, int rawCount, int skipped) {

    /**
     * Creates the result; the list is copied unmodifiably.
     */
    Mapped {
        items = List.copyOf(items);
    }
}
