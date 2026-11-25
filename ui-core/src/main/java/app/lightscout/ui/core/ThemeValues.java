package app.lightscout.ui.core;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.TypedValue;

/**
 * Resolves colour and text-size attributes from a themed context (CMP-30 roles for CMP-32 drawing).
 */
final class ThemeValues {

    private ThemeValues() {
    }

    /**
     * Resolves a colour attribute.
     *
     * @param themed   themed context
     * @param attr     attribute id
     * @param fallback colour used when the theme does not define the attribute
     * @return the ARGB colour
     */
    static int color(Context themed, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!themed.getTheme().resolveAttribute(attr, value, true)) {
            return fallback;
        }
        if (value.resourceId != 0) {
            return themed.getColor(value.resourceId);
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return fallback;
    }

    /**
     * Resolves the text size of a text-appearance attribute (e.g. {@code textAppearanceLabelSmall}).
     *
     * @param themed     themed context
     * @param appearance text-appearance attribute id
     * @param fallbackSp size used when it cannot be resolved
     * @return the size in px (font scale applied)
     */
    static float textSizePx(Context themed, int appearance, float fallbackSp) {
        float fallback = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fallbackSp,
                themed.getResources().getDisplayMetrics());
        TypedValue value = new TypedValue();
        if (!themed.getTheme().resolveAttribute(appearance, value, true) || value.resourceId == 0) {
            return fallback;
        }
        TypedArray a = themed.obtainStyledAttributes(value.resourceId, new int[] {android.R.attr.textSize});
        try {
            return a.getDimension(0, fallback);
        } finally {
            a.recycle();
        }
    }
}
