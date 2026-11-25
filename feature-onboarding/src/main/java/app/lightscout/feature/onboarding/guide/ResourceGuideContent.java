package app.lightscout.feature.onboarding.guide;

import android.content.res.Resources;

import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link GuideContent} over the bundled string arrays (ST-08 per release; CP-00A R-85): {@code s08_steps_<option>}
 * and the parallel {@code s08_steps_<option>_open} array of {@link OemSettingId} names (empty item = text-only step).
 * An unknown name or a missing parallel item gives a text-only step (never a crash).
 */
public final class ResourceGuideContent implements GuideContent {

    private final Resources resources;

    /**
     * Creates the loader.
     *
     * @param resources the module resources
     */
    public ResourceGuideContent(Resources resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    @Override
    public List<GuideStep> steps(Manufacturer manufacturer) {
        int textsId = GuideCopy.steps(manufacturer);
        if (textsId == 0) {
            return List.of();
        }
        String[] texts = resources.getStringArray(textsId);
        String[] settings = resources.getStringArray(GuideCopy.settings(manufacturer));
        List<GuideStep> out = new ArrayList<>(texts.length);
        for (int i = 0; i < texts.length; i++) {
            out.add(new GuideStep(texts[i], i < settings.length ? parse(settings[i]) : null));
        }
        return out;
    }

    /**
     * Parses one item of a parallel settings array.
     *
     * @param name an {@link OemSettingId} name or an empty string
     * @return the page, or {@code null} for an empty or unknown name
     */
    static OemSettingId parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return OemSettingId.valueOf(name.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
