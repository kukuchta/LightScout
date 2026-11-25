package app.lightscout.usecases;

/**
 * Result of IF-24 {@code save} (CP-00A R-10, R-71).
 *
 * @param serverChanged {@code true} when the server identity changed, so cache and monitor / alarm state were reset
 */
public record SaveResult(boolean serverChanged) {
}
