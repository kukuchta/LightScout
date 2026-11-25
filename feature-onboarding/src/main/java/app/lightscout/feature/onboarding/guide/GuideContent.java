package app.lightscout.feature.onboarding.guide;

import app.lightscout.platform.Manufacturer;

import java.util.List;

/**
 * Source of the bundled manufacturer guides (ST-08, AQ-02). Production: {@link ResourceGuideContent} over the
 * {@code s08_steps_<option>} string arrays (CP-00A R-85); JVM tests: an in-memory list.
 */
public interface GuideContent {

    /**
     * Returns the steps of one manufacturer.
     *
     * @param manufacturer the C-S08-03 option
     * @return the steps in order; empty for „Google Pixel” and „Other” (Empty state)
     */
    List<GuideStep> steps(Manufacturer manufacturer);
}
