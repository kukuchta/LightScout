package app.lightscout.domain.port;

/**
 * Readiness count port (IF-65, CP-00A R-33), implemented by {@code :platform}.
 */
public interface ReadinessSource {

    /**
     * Returns the number of required readiness items that are missing (local checks only).
     *
     * @return count
     */
    int missingRequiredCount();
}
