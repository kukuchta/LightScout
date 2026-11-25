package app.lightscout.ui.core;

import app.lightscout.domain.model.EntryType;
/**
 * IF-29 — cross-feature navigation (CP-00A R-12, R-38; 03 §6.6, ADR-027; CP-00 R-4).
 *
 * <p>Implemented in {@code :app} (CP-15) by navigating to destination ids of the nested feature graphs. Called on
 * the main thread. Navigation inside a feature (e.g. S-05 → S-06 → S-07 → S-08) uses the feature's own graph
 * actions, not this interface.
 */
public interface Navigator {

    /**
     * Opens S-02 Entry form.
     *
     * @param identifier non-null, nonempty Nightscout identifier of the entry to edit
     */
    void openEntry(String identifier);

    /** Opens a new entry with one of the five loggable types (R-95). */
    void openNewEntry(EntryType initialType);

    /** Opens S-03 Statistics. */
    void openStatistics();

    /** Opens S-04 Settings. */
    void openSettings();

    /**
     * Opens S-06 Nightscout connection.
     *
     * @param mode onboarding or edit
     */
    void openConnection(ConnectionMode mode);

    /**
     * Opens S-07 Alarm readiness.
     *
     * @param mode onboarding or check
     */
    void openReadiness(ReadinessMode mode);

    /** S-07 „Done”: opens S-01 with the onboarding back stack cleared. */
    void finishOnboarding();

    /**
     * Pops the current destination and delivers a message to the one below (see {@link NavResults}).
     *
     * @param message the result message
     */
    void finishWithResult(UiMessage message);
}
