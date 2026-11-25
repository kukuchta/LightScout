package app.lightscout.usecases;

/**
 * CMP-17 OnboardingState — IF-27 (CP-02 §8.9, CP-00A R-31, R-72). All methods are blocking (CE prefs, connection
 * store): call off the main thread (R-34).
 */
public interface OnboardingState {

    /**
     * Resolves the start destination; used only on a cold start without a restored back stack (03 §6.8).
     *
     * @return the start destination
     */
    StartDestination resolve();

    /** Persists the disclaimer acceptance (S-05 „Continue”). */
    void acceptDisclaimer();

    /**
     * Completes onboarding (S-07 „Done”): {@code onboardingCompleted = true} (CE), {@code monitoringConfigured = true}
     * (DE), then {@code MonitorControl.startMonitoring()} and an {@code ONBOARDING_DONE} tick request.
     */
    void completeOnboarding();
}
