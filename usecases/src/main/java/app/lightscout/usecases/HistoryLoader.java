package app.lightscout.usecases;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.statistics.Period;

import java.time.Instant;
import java.util.List;

/**
 * CMP-12 HistoryLoader — IF-22 (CP-02 §8.4, CP-00A R-26): cache first, fetch on demand (04 §8 coverage rule).
 */
public interface HistoryLoader {

    /**
     * Returns the current server epoch. Consumers use this to distinguish a server/data reset from view-only
     * lifecycle changes; implementations without epoch tracking may keep the default stable value.
     *
     * @return the current server epoch
     */
    default long serverEpoch() {
        return 0L;
    }

    /**
     * Loads a chart window. Work and callback on {@code ioExecutor}. A range with {@code from ≥ completeFrom} is
     * served from the cache (online or offline); otherwise the uncovered part is fetched when online, else a
     * {@code NO_INTERNET} failure is delivered.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @param cb   receives the segment or a failure
     */
    void loadSegment(Instant from, Instant to, Callback<Segment> cb);

    /**
     * Reads chart data currently present in the local cache without consulting coverage metadata, connectivity or
     * Nightscout. A successful result contains available cached points only; it does not assert that the requested
     * window is complete. Work and callback run on {@code ioExecutor}.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @param cb   receives available cached data or a storage failure
     */
    void loadCachedSegment(Instant from, Instant to, Callback<Segment> cb);

    /**
     * Loads the readings of a statistics period ({@code ReadingRules.displayable}), same coverage rule. Work and
     * callback on {@code ioExecutor}.
     *
     * @param p  the period
     * @param cb receives the readings or a failure
     */
    void loadPeriod(Period p, Callback<List<Reading>> cb);

    /**
     * Returns cached readings only (S-12 mini chart). Blocking; any background thread, never the main thread.
     *
     * @param from inclusive start
     * @param to   exclusive end
     * @return displayable readings; empty when the cache cannot be read
     */
    List<Reading> cachedReadings(Instant from, Instant to);
}
