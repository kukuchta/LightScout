package app.lightscout.feature.home;

import app.lightscout.domain.status.CheckCycle;

/**
 * R-93 / D-151 display-only phases of the next-reading track; never changes snapshot overdue / alarm semantics or
 * requests data. The track shows the time to the next planned check.
 *
 * <ul>
 *   <li>FRESH: the slow cycle to the first planned check (accent).</li>
 *   <li>WAITING: the check found nothing new yet, a short cycle (accent).</li>
 *   <li>MISSED: the check failed, or found nothing twice in a row, or the reading is overdue (orange).</li>
 *   <li>STALE: no data for the threshold (grey).</li>
 * </ul>
 */
record FreshnessPresentation(Phase phase, int percent, long elapsedMs, long spanMs) {
    enum Phase { EMPTY, LOADING, FRESH, WAITING, MISSED, STALE }

    static boolean shouldAnimate(boolean started, Phase phase, boolean visible, boolean motionEnabled) {
        return started && phase == Phase.LOADING && visible && motionEnabled;
    }

    static FreshnessPresentation from(HomeUiState state) {
        // D-148: the sweep is for "nothing to show yet".
        if (state.loading() && state.sgv() == null) {
            return new FreshnessPresentation(Phase.LOADING, 0, 0L, 0L);
        }
        if (state.sgv() == null) {
            return new FreshnessPresentation(Phase.EMPTY, 0, 0L, 0L);
        }
        HomeUiState.Progress progress = state.progress();
        CheckCycle.Kind kind = progress.kind();
        boolean determinate = progress.mode() == HomeUiState.ProgressMode.DETERMINATE;
        Phase phase;
        if (state.stale() || kind == CheckCycle.Kind.NO_DATA) {
            phase = Phase.STALE;
        } else if (progress.mode() == HomeUiState.ProgressMode.OVERDUE || kind == CheckCycle.Kind.MISSED) {
            phase = Phase.MISSED;
        } else if (kind == CheckCycle.Kind.WAITING) {
            phase = Phase.WAITING;
        } else {
            phase = Phase.FRESH;
        }
        return new FreshnessPresentation(phase, determinate ? progress.percent() : 100,
                determinate ? progress.elapsedMs() : 0L, determinate ? progress.spanMs() : 0L);
    }
}
