package app.lightscout.platform;

/**
 * CMP-28: detects the phone manufacturer for S-08 (CP-05 §8.7, CP-00A R-85).
 */
public interface ManufacturerDetector {

    /**
     * Detects the manufacturer of this phone.
     *
     * @return one of the six options; {@link Manufacturer#OTHER} when unknown
     */
    Manufacturer detect();
}
