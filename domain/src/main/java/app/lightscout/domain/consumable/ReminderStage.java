package app.lightscout.domain.consumable;

/**
 * Reminder stage (DS:D-026, CP-00A R-8).
 */
public enum ReminderStage {
    /** 24 h before expiry. */
    DUE_TOMORROW,
    /** At expiry. */
    DUE_NOW
}
