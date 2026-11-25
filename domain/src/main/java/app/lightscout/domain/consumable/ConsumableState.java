package app.lightscout.domain.consumable;

/**
 * Counter state of a consumable (design S-01, DS:D-026, CP-00A R-8, R-62).
 */
public enum ConsumableState {
    /** No change entry found. */
    NONE_FOUND,
    /**
     * No change entry cached yet, but the first backfill of this connection is still running, so one may still
     * arrive (DCR-45): shown as „Loading…” instead of the false „No records”. Bounded in time, never permanent.
     */
    LOADING,
    /** Before {@code due24hAt}. */
    NORMAL,
    /** From {@code due24hAt} until {@code dueAt} („Change soon”). */
    CHANGE_SOON,
    /** From {@code dueAt} („Expired”). */
    EXPIRED
}
