package app.lightscout.domain.port;

/**
 * Per-install flags (IF-60, AR-11; CP-00A R-39). CE methods must not be called while locked;
 * {@code monitoringConfigured} and {@code handledBootCount} are device-protected (ST-05).
 */
public interface AppFlagsStore {

    /**
     * Returns whether the disclaimer was accepted (CE).
     *
     * @return flag
     */
    boolean disclaimerAccepted();

    /** Marks the disclaimer accepted (CE). */
    void setDisclaimerAccepted();

    /**
     * Returns whether onboarding was completed (CE).
     *
     * @return flag
     */
    boolean onboardingCompleted();

    /**
     * Sets onboarding completed (CE).
     *
     * @param v value
     */
    void setOnboardingCompleted(boolean v);

    /**
     * Returns whether monitoring is configured (DE).
     *
     * @return flag
     */
    boolean monitoringConfigured();

    /**
     * Sets monitoring configured (DE).
     *
     * @param v value
     */
    void setMonitoringConfigured(boolean v);

    /**
     * Returns the last handled boot count (DE).
     *
     * @return boot count
     */
    long handledBootCount();

    /**
     * Sets the last handled boot count (DE).
     *
     * @param c boot count
     */
    void setHandledBootCount(long c);
}
