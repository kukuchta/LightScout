package app.lightscout.ui.core;

/**
 * Pure GIR-6 inset arithmetic (CP-06 §7.3, §8.3) used by {@link EdgeToEdgeInsets}. All values in px.
 */
final class InsetMath {

    /** Margin of sticky bottom buttons above the navigation bar, and FAB clearance (GIR-6). */
    static final int STICKY_MARGIN_DP = 16;
    /** Minimum distance of the S-09 bottom edge above the navigation bar (M3-02). */
    static final int ALARM_BOTTOM_DP = 24;
    /** S-09 side insets (M3-02). */
    static final int ALARM_SIDE_DP = 16;

    private InsetMath() {
    }

    /**
     * Scroll content bottom padding: own padding + navigation-bar inset (+ FAB height + 16 dp on S-01).
     *
     * @param initialPadding padding from the layout
     * @param navBar         navigation-bar bottom inset
     * @param fabHeight      FAB height, 0 when there is no FAB
     * @param marginPx       16 dp in px (used only with a FAB)
     * @return the bottom padding
     */
    static int scrollBottomPadding(int initialPadding, int navBar, int fabHeight, int marginPx) {
        int fab = fabHeight > 0 ? fabHeight + marginPx : 0;
        return initialPadding + Math.max(0, navBar) + fab;
    }

    /**
     * Bottom margin of a sticky button (S-05, S-06, S-07) or a FAB: navigation-bar inset + 16 dp.
     *
     * @param navBar   navigation-bar bottom inset
     * @param marginPx 16 dp in px
     * @return the bottom margin
     */
    static int stickyBottomMargin(int navBar, int marginPx) {
        return Math.max(0, navBar) + marginPx;
    }

    /**
     * Bottom padding of an IME-resizing screen (S-02, S-06): the larger of keyboard and navigation bar.
     *
     * @param initialPadding padding from the layout
     * @param ime            IME bottom inset (0 when hidden)
     * @param navBar         navigation-bar bottom inset
     * @return the bottom padding
     */
    static int imeBottomPadding(int initialPadding, int ime, int navBar) {
        return initialPadding + Math.max(Math.max(0, ime), Math.max(0, navBar));
    }

    /**
     * Side margin: system-bar side inset + extra.
     *
     * @param sideInset system-bar inset on that side
     * @param extraPx   extra margin
     * @return the margin
     */
    static int sideMargin(int sideInset, int extraPx) {
        return Math.max(0, sideInset) + extraPx;
    }
}
