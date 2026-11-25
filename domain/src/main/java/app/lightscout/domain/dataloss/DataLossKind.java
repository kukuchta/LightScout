package app.lightscout.domain.dataloss;

/**
 * Kind of data-loss status (CP-00A R-5).
 */
public enum DataLossKind {
    /** Readings are arriving. */
    NONE,
    /** No reading ever and the threshold since the first-use anchor has not passed (AR-49, R-61). */
    FIRST_USE,
    /** No new reading for at least the threshold (G-23 „Data loss”). */
    DATA_LOSS
}
