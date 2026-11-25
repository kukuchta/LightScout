package app.lightscout.domain.model;

/**
 * Glucose trend direction of a reading (G-02, 05 §4.1, CP-00A R-2).
 *
 * <p>Nightscout directions {@code DoubleUp … DoubleDown} map 1:1; empty, {@code NOT COMPUTABLE},
 * {@code RATE OUT OF RANGE}, {@code NONE} and anything else map to {@link #UNKNOWN} (mapping is CP-03).
 */
public enum Trend {
    /** Nightscout {@code DoubleUp} — rising quickly. */
    DOUBLE_UP,
    /** Nightscout {@code SingleUp} — rising. */
    SINGLE_UP,
    /** Nightscout {@code FortyFiveUp} — rising slowly. */
    FORTY_FIVE_UP,
    /** Nightscout {@code Flat} — steady. */
    FLAT,
    /** Nightscout {@code FortyFiveDown} — falling slowly. */
    FORTY_FIVE_DOWN,
    /** Nightscout {@code SingleDown} — falling. */
    SINGLE_DOWN,
    /** Nightscout {@code DoubleDown} — falling quickly. */
    DOUBLE_DOWN,
    /** No or unrecognised direction — trend unknown. */
    UNKNOWN
}
