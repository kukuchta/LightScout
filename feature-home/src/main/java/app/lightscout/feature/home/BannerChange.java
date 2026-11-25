package app.lightscout.feature.home;

import app.lightscout.feature.home.HomeUiState.BannerUi;

import java.util.Objects;

/**
 * How the C-S01-06 banner views must change between two renders (CP-12 §7.11, M3-14; IR-103 Moderator decision):
 * the polite live region announces only when the banner kind changes (new banner or a different priority), not when
 * the per-minute {n}, the action state or anything else of the same banner changes.
 */
public enum BannerChange {
    /** Same banner content (or still no banner): touch nothing. */
    NONE,
    /** The banner disappears. */
    HIDE,
    /** First render of a fresh view (e.g. after recreation): show it, no announcement decision (layout default). */
    INITIAL,
    /** A new banner or a different kind: render and announce (polite live region on). */
    ANNOUNCE,
    /** Same kind, other content ({n}, action enabled): update without re-triggering the announcement. */
    QUIET;

    /**
     * Decides the change.
     *
     * @param firstRender this is the first render of the current view
     * @param previous    the banner rendered last on this view, or {@code null}
     * @param next        the banner to render, or {@code null}
     * @return the change
     */
    public static BannerChange of(boolean firstRender, BannerUi previous, BannerUi next) {
        if (next == null) {
            return firstRender || previous != null ? HIDE : NONE;
        }
        if (firstRender) {
            return INITIAL;
        }
        if (previous == null || previous.kind() != next.kind()) {
            return ANNOUNCE;
        }
        return Objects.equals(previous, next) ? NONE : QUIET;
    }
}
