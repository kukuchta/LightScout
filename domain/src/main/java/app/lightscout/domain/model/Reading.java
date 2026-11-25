package app.lightscout.domain.model;

import java.time.Instant;

/**
 * One CGM glucose reading (G-02 „Reading”, ENT-01, 03 §6.1).
 *
 * @param id     Nightscout {@code identifier}
 * @param time   reading time (UTC)
 * @param sgv    glucose in mg/dL; every {@code sgv ≥ 1} counts as a reading (M-10)
 * @param trend  direction, {@link Trend#UNKNOWN} if none
 * @param device uploader device name as sent by Nightscout
 */
public record Reading(String id, Instant time, int sgv, Trend trend, String device) {
}
