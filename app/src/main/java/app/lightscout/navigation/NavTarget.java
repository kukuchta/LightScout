package app.lightscout.navigation;

/**
 * Destinations that {@code :app} navigates to (CP-15 §7, CP-00A R-38). The Android adapter
 * ({@link NavControllerPort}) maps them to the destination ids of the feature graphs; the decision logic
 * ({@link AppNavigator}, {@link StartRoute}, {@link NavLinks}) stays free of Android types so that it is JVM-tested.
 */
public enum NavTarget {
    /** {@code home_graph} (S-01 {@code homeFragment}); also a possible start destination of {@code root_graph}. */
    HOME,
    /** {@code onboarding_graph} (start: S-05 {@code disclaimerFragment}); the other possible root start. */
    ONBOARDING,
    /** S-06 {@code connectionFragment} (argument {@code mode}; reached by internal deep link, {@link NavLinks}). */
    CONNECTION,
    /** S-07 {@code readinessFragment} (argument {@code mode}; reached by internal deep link, {@link NavLinks}). */
    READINESS,
    /** {@code entry_graph} (start: S-02 {@code entryFormFragment}; argument {@code entryId}, nullable = create). */
    ENTRY,
    /** {@code stats_graph} (start: S-03 {@code statisticsFragment}; no arguments). */
    STATISTICS,
    /** {@code settings_graph} (start: S-04 {@code settingsFragment}; no arguments). */
    SETTINGS
}
