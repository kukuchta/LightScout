package app.lightscout.usecases.common;

import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Time-sliced window fetch (05 C-7, C-8; CP-00A R-23). The caller owns slicing: a window is cut into
 * ≤ 24 h slices; a slice that returns {@code limit} documents ({@code complete = false}) is split in half and
 * re-fetched, down to 1 h; a slice of 1 h or less that is still incomplete is kept for upsert only.
 * The first failing call stops the fetch (no in-cycle retry — C-4).
 *
 * <p>BUG-18 guards (a server that does not honour the window must never make the fetch loop):
 * <ul>
 *   <li>an incomplete page with no document inside its window means the server ignored the window. The production
 *       gateway drops documents outside the window, so the RD-1 response (the same oldest 1000 documents for every
 *       window) is caught here after <b>one</b> call;</li>
 *   <li>defence in depth for a gateway that does not trim: two halves that are both incomplete and both return the
 *       parent's page again (3 calls);</li>
 *   <li>each top-level 24 h slice may issue at most {@link #MAX_CALLS_PER_SLICE} calls; later splits are skipped
 *       (the slice stays incomplete, upsert only).</li>
 * </ul>
 * The first two stop the whole fetch and report {@link Fetched#windowIgnored()}.
 */
public final class SlicedWindowFetcher {

    /** Largest slice per call (C-8). */
    public static final Duration MAX_SLICE = Duration.ofHours(24);

    /** Slices of this length or shorter are not split again (R-23). */
    public static final Duration MIN_SPLIT = Duration.ofHours(1);

    /** {@code limit} for IF-34 / IF-35 window calls (C-7). */
    public static final int WINDOW_LIMIT = 1000;

    /**
     * BUG-18: call budget of one top-level slice (parent + split halves). 15 = a full split tree down to 3 h; an
     * honest server needs 3 calls for a day of 1-min CGM data.
     */
    public static final int MAX_CALLS_PER_SLICE = 15;

    private SlicedWindowFetcher() {
    }

    /**
     * One window call of the gateway.
     *
     * @param <T> document type
     */
    @FunctionalInterface
    public interface WindowCall<T> {

        /**
         * Fetches documents with time in {@code [from, to)}.
         *
         * @param from  inclusive start
         * @param to    exclusive end
         * @param limit maximum documents
         * @return the page or a failure
         */
        Result<Page<T>> fetch(Instant from, Instant to, int limit);
    }

    /**
     * Store side of a window: upsert or set-difference replace.
     *
     * @param <T> document type
     */
    public interface WindowSink<T> {

        /**
         * Inserts or updates documents.
         *
         * @param items documents
         */
        void upsert(List<T> items);

        /**
         * Replaces {@code [from, to)} with a complete server set (deletes missing rows).
         *
         * @param from  inclusive start
         * @param to    exclusive end
         * @param items the complete server set
         */
        void replaceWindow(Instant from, Instant to, List<T> items);
    }

    /**
     * One fetched slice.
     *
     * @param from     inclusive start
     * @param to       exclusive end
     * @param items    documents returned
     * @param complete {@code Page.complete} of the call
     * @param split    {@code true} when this incomplete slice was re-fetched as two halves
     * @param <T>      document type
     */
    public record Slice<T>(Instant from, Instant to, List<T> items, boolean complete, boolean split) {

        /**
         * Creates the slice.
         */
        public Slice {
            items = List.copyOf(items);
        }

        /**
         * Tells whether this incomplete slice was split again (its halves cover the window).
         *
         * @return {@code true} when split
         */
        public boolean wasSplit() {
            return split;
        }

        private Slice<T> asSplit() {
            return new Slice<>(from, to, items, complete, true);
        }
    }

    /**
     * Result of a sliced fetch, in call order (a split parent precedes its halves).
     *
     * @param slices        fetched slices
     * @param failure       outcome of the failing call, or {@code null} when every call succeeded
     * @param failureDetail detail code of the failing call; nullable
     * @param windowIgnored {@code true} when the server did not honour the window (BUG-18); the fetch stopped there
     * @param <T>           document type
     */
    public record Fetched<T>(List<Slice<T>> slices, FetchOutcome failure, String failureDetail,
                             boolean windowIgnored) {

        /**
         * Creates the result.
         */
        public Fetched {
            slices = List.copyOf(slices);
        }

        /**
         * Tells whether a call failed.
         *
         * @return {@code true} when a call failed
         */
        public boolean failed() {
            return failure != null;
        }

        /**
         * Tells whether the whole window is covered by complete slices and no call failed.
         *
         * @return {@code true} when the window is known completely
         */
        public boolean fullyComplete() {
            if (failed()) {
                return false;
            }
            for (Slice<T> s : slices) {
                if (!s.complete() && !s.wasSplit()) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Returns every document of every slice (duplicates possible after a split).
         *
         * @return documents
         */
        public List<T> allItems() {
            List<T> all = new ArrayList<>();
            for (Slice<T> s : slices) {
                all.addAll(s.items());
            }
            return all;
        }
    }

    /**
     * Cuts {@code [from, to)} into consecutive slices of at most {@link #MAX_SLICE}, oldest first.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @return pairs {@code [start, end]}
     */
    public static List<Instant[]> slices(Instant from, Instant to) {
        List<Instant[]> result = new ArrayList<>();
        Instant start = from;
        while (start.isBefore(to)) {
            Instant end = start.plus(MAX_SLICE);
            if (end.isAfter(to)) {
                end = to;
            }
            result.add(new Instant[] {start, end});
            start = end;
        }
        return result;
    }

    /**
     * Fetches {@code [from, to)} in slices, splitting incomplete slices.
     *
     * @param call  the gateway call
     * @param from  inclusive start
     * @param to    exclusive end
     * @param limit {@code limit} per call
     * @param <T>   document type
     * @return the slices in call order
     */
    public static <T> Fetched<T> fetch(WindowCall<T> call, Instant from, Instant to, int limit) {
        Objects.requireNonNull(call, "call");
        List<Slice<T>> out = new ArrayList<>();
        for (Instant[] s : slices(from, to)) {
            int[] budget = {MAX_CALLS_PER_SLICE - 1};
            Result<Page<T>> r = call.fetch(s[0], s[1], limit);
            if (!r.ok()) {
                return new Fetched<>(out, r.outcome(), r.detailCode(), false);
            }
            Step<T> step = expand(call, slice(s[0], s[1], r), limit, out, budget);
            if (step.failure() != null) {
                return new Fetched<>(out, step.failure().outcome(), step.failure().detailCode(), false);
            }
            if (step.ignored()) {
                return new Fetched<>(out, null, null, true);
            }
        }
        return new Fetched<>(out, null, null, false);
    }

    /** Result of expanding one slice: the failing call, or whether the server ignored the window. */
    private record Step<T>(Result<Page<T>> failure, boolean ignored) {
    }

    private static <T> Slice<T> slice(Instant from, Instant to, Result<Page<T>> r) {
        Page<T> page = r.value();
        List<T> items = page == null ? List.of() : page.items();
        return new Slice<>(from, to, items, page != null && page.complete(), false);
    }

    /**
     * Adds {@code slice} (already fetched) and, when it is incomplete and longer than {@link #MIN_SPLIT}, fetches
     * both halves first, checks the BUG-18 guards, then expands each half (depth first, a parent precedes its
     * halves).
     */
    private static <T> Step<T> expand(WindowCall<T> call, Slice<T> slice, int limit, List<Slice<T>> out,
                                      int[] budget) {
        if (slice.complete()) {
            out.add(slice);
            return new Step<>(null, false);
        }
        if (slice.items().isEmpty()) {
            // `limit` documents, none inside the window: the server answered something else (BUG-18)
            out.add(slice);
            return new Step<>(null, true);
        }
        if (Duration.between(slice.from(), slice.to()).compareTo(MIN_SPLIT) <= 0) {
            out.add(slice);
            return new Step<>(null, false);
        }
        if (budget[0] < 2) {
            out.add(slice);   // budget spent: kept incomplete, upsert only
            return new Step<>(null, false);
        }
        Instant mid = slice.from().plus(Duration.between(slice.from(), slice.to()).dividedBy(2));
        budget[0] -= 2;
        Result<Page<T>> left = call.fetch(slice.from(), mid, limit);
        if (!left.ok()) {
            out.add(slice);
            return new Step<>(left, false);
        }
        Slice<T> l = slice(slice.from(), mid, left);
        Result<Page<T>> right = call.fetch(mid, slice.to(), limit);
        if (!right.ok()) {
            out.add(slice);
            out.add(l);
            return new Step<>(right, false);
        }
        Slice<T> r = slice(mid, slice.to(), right);
        if (!l.complete() && !r.complete() && l.items().equals(slice.items()) && r.items().equals(slice.items())) {
            // both halves repeat the parent page: the window filter is not honoured (BUG-18; defence in depth for
            // a gateway that does not drop documents outside the window)
            out.add(slice);
            return new Step<>(null, true);
        }
        out.add(slice.asSplit());
        Step<T> first = expand(call, l, limit, out, budget);
        if (first.failure() != null || first.ignored()) {
            return first;
        }
        return expand(call, r, limit, out, budget);
    }

    /**
     * Stores a fetch result: after any failure everything is upserted without deletes (SY-3, C-8); otherwise
     * complete slices replace their window (set difference) and incomplete slices are upserted, in call order.
     * An incomplete page never deletes anything.
     *
     * @param fetched the fetch result
     * @param sink    the store side
     * @param <T>     document type
     */
    public static <T> void store(Fetched<T> fetched, WindowSink<T> sink) {
        if (fetched.failed()) {
            List<T> all = fetched.allItems();
            if (!all.isEmpty()) {
                sink.upsert(all);
            }
            return;
        }
        for (Slice<T> s : fetched.slices()) {
            if (s.complete()) {
                sink.replaceWindow(s.from(), s.to(), s.items());
            } else if (!s.items().isEmpty()) {
                sink.upsert(s.items());
            }
        }
    }
}
