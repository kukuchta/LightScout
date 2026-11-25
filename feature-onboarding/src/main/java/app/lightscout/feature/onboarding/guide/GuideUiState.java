package app.lightscout.feature.onboarding.guide;

import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;

import java.util.List;

/**
 * What S-08 shows (CP-14 §7.5).
 *
 * @param manufacturer the selected C-S08-03 option
 * @param steps        the C-S08-04 steps of that option; empty → „Your phone needs no extra steps beyond battery
 *                     optimisation.”
 */
public record GuideUiState(Manufacturer manufacturer, List<Step> steps) {

    /**
     * Creates the state with an unmodifiable copy of the steps.
     *
     * @param manufacturer see the record description
     * @param steps        see the record description
     */
    public GuideUiState {
        steps = List.copyOf(steps);
    }

    /**
     * Tells whether the Empty — no results state is shown.
     *
     * @return {@code true} when the option has no extra steps
     */
    public boolean noExtraSteps() {
        return steps.isEmpty();
    }

    /**
     * One numbered step.
     *
     * @param number 1-based step number
     * @param total  number of steps („Step {n} of {m}”)
     * @param text   the instruction
     * @param open   the page of the C-S08-05 „Open …” button, or {@code null} when the step has none or its intent
     *               does not resolve on this phone (VR-05 fallback: hidden)
     */
    public record Step(int number, int total, String text, OemSettingId open) {
    }
}
