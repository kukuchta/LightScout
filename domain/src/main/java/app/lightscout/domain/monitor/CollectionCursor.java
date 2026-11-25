package app.lightscout.domain.monitor;

import java.time.Instant;

/**
 * Sync cursor of one Nightscout collection (ENT-07, CP-00A R-6, R-21).
 *
 * @param lastModifiedSeen last {@code lastModified} seen in epoch ms; nullable
 * @param hotWindowFrom    start of the hot window; nullable
 * @param lastReconcile    last set-difference reconcile; nullable
 * @param completeFrom     cache is complete from this instant (04 §8 {@code completeFromMs}); nullable
 * @param historyCapable   SY-7 probe result
 * @param historyCursorMs  IF-37 {@code srvModified} cursor once capable; nullable
 */
public record CollectionCursor(Long lastModifiedSeen, Instant hotWindowFrom,
                               Instant lastReconcile, Instant completeFrom,
                               boolean historyCapable,
                               Long historyCursorMs) {
}
