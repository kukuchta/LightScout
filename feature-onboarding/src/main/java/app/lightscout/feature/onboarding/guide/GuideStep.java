package app.lightscout.feature.onboarding.guide;

import app.lightscout.platform.OemSettingId;

import java.util.Objects;

/**
 * One bundled guide step (CP-14 §7.5 Steps, CP-00A R-85): the instruction text and the optional IF-56 settings page
 * of its „Open {setting name}” button.
 *
 * @param text    the instruction (string-array item of {@code s08_steps_<option>})
 * @param setting the page the step can open, or {@code null} for a text-only step
 */
public record GuideStep(String text, OemSettingId setting) {

    /**
     * Validates the step.
     *
     * @param text    see the record description
     * @param setting see the record description
     */
    public GuideStep {
        Objects.requireNonNull(text, "text");
    }
}
