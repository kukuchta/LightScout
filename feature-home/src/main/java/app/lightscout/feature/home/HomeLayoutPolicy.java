package app.lightscout.feature.home;

/** Font-scale and measured-height policy for the Home content viewport (CP-00A R-96/R-97). */
public final class HomeLayoutPolicy {

    private HomeLayoutPolicy() {
    }

    /** Large-font content keeps its accessibility scrolling allowance. */
    public static boolean allowsScrolling(float fontScale) {
        return fontScale >= 1.5f;
    }

    /**
     * Applies the large-font allowance, or scrolls standard-font content only when its fixed content and usable
     * chart minimum exceed the measured viewport. Heights are pixels measured after system insets.
     */
    public static boolean shouldScroll(float fontScale, int viewportHeightPx, int fixedContentHeightPx,
            int minimumChartHeightPx) {
        if (allowsScrolling(fontScale)) {
            return true;
        }
        long requiredHeight = (long) Math.max(0, fixedContentHeightPx) + Math.max(0, minimumChartHeightPx);
        return requiredHeight > Math.max(0, viewportHeightPx);
    }

    /** Lets the glucose row and status chips wrap before large system text can overrun a standard viewport. */
    public static boolean requiresWrapping(float fontScale) {
        return fontScale > 1.0f;
    }
}
