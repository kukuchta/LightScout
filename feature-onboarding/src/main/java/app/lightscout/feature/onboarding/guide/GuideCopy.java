package app.lightscout.feature.onboarding.guide;

import androidx.annotation.ArrayRes;
import androidx.annotation.StringRes;

import app.lightscout.feature.onboarding.R;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;

/**
 * Maps S-08 options and settings pages to their resources (CP-14 §7.5, §7.7; CP-00A R-85). Pure lookup,
 * JVM-tested.
 */
public final class GuideCopy {

    private GuideCopy() {
    }

    /**
     * C-S08-03 option label.
     *
     * @param m the option
     * @return {@code s08_mfr_*}
     */
    @StringRes
    public static int label(Manufacturer m) {
        return switch (m) {
            case XIAOMI -> R.string.s08_mfr_xiaomi;
            case SAMSUNG -> R.string.s08_mfr_samsung;
            case HUAWEI -> R.string.s08_mfr_huawei;
            case ONEPLUS -> R.string.s08_mfr_oneplus;
            case PIXEL -> R.string.s08_mfr_pixel;
            case OTHER -> R.string.s08_mfr_other;
        };
    }

    /**
     * Step texts of an option ({@code s08_steps_<option>}).
     *
     * @param m the option
     * @return the string-array id; {@code 0} for „Google Pixel” and „Other” (no extra steps)
     */
    @ArrayRes
    public static int steps(Manufacturer m) {
        return switch (m) {
            case XIAOMI -> R.array.s08_steps_xiaomi;
            case SAMSUNG -> R.array.s08_steps_samsung;
            case HUAWEI -> R.array.s08_steps_huawei;
            case ONEPLUS -> R.array.s08_steps_oneplus;
            case PIXEL, OTHER -> 0;
        };
    }

    /**
     * Parallel {@code OemSettingId} names of an option's steps ({@code s08_steps_<option>_open}; an empty item means
     * no button).
     *
     * @param m the option
     * @return the string-array id; {@code 0} for „Google Pixel” and „Other”
     */
    @ArrayRes
    public static int settings(Manufacturer m) {
        return switch (m) {
            case XIAOMI -> R.array.s08_steps_xiaomi_open;
            case SAMSUNG -> R.array.s08_steps_samsung_open;
            case HUAWEI -> R.array.s08_steps_huawei_open;
            case ONEPLUS -> R.array.s08_steps_oneplus_open;
            case PIXEL, OTHER -> 0;
        };
    }

    /**
     * The „{setting name}” of a C-S08-05 button („Open {setting name}”).
     *
     * @param id the page
     * @return {@code s08_setting_*}
     */
    @StringRes
    public static int settingName(OemSettingId id) {
        return switch (id) {
            case XIAOMI_AUTOSTART -> R.string.s08_setting_autostart;
            case XIAOMI_BATTERY_SAVER -> R.string.s08_setting_battery_saver;
            case APP_DETAILS -> R.string.s08_setting_app_details;
        };
    }
}
