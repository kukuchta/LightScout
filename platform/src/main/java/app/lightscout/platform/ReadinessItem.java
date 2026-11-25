package app.lightscout.platform;

/**
 * One evaluated readiness item (CP-00A R-11).
 *
 * @param id       the item
 * @param shown    whether S-07 shows the item on this phone (API band and state rules of CP-05 §8.2)
 * @param required {@code true} for required items, {@code false} for the recommended item 6
 * @param status   the evaluated status
 */
public record ReadinessItem(ReadinessItemId id, boolean shown, boolean required, ReadinessStatus status) {

    /**
     * Tells whether this item counts towards {@code missingRequiredCount()}: shown, required and not done.
     *
     * @return {@code true} when the item is missing
     */
    public boolean missingRequired() {
        return shown && required && status == ReadinessStatus.NEEDS_ATTENTION;
    }
}
