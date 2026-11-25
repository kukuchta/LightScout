package app.lightscout.platform;

import java.util.List;

import app.lightscout.domain.port.ReadinessSource;

/**
 * CMP-27: local alarm-readiness checks behind S-07 items 1–7 (CP-05 §8.2, CP-00A R-11). Implements IF-65.
 *
 * <p>Checks are system-service queries only (no disk, no network) and are cheap enough for every tick (F15).
 */
public interface ReadinessChecker extends ReadinessSource {

    /**
     * Evaluates all seven items for the current API band.
     *
     * @return the items in display order 1–5, 7, 6 (item 6 „Manufacturer settings” always last)
     */
    List<ReadinessItem> check();

    /**
     * Returns the number of shown required items that are not done. Item 6 is never counted.
     *
     * @return count
     */
    @Override
    int missingRequiredCount();
}
