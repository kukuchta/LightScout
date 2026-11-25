package app.lightscout.domain.model;

/**
 * One step of a profile basal schedule (profile {@code basal[]}, CP-00A R-2).
 *
 * @param startSecondOfDay {@code timeAsSeconds} — start of the step in seconds after local midnight
 * @param rateMilliPerHour basal rate in milli-units per hour (U/h × 1000)
 */
public record BasalStep(int startSecondOfDay, int rateMilliPerHour) {
}
