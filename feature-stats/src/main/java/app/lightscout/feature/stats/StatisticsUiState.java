package app.lightscout.feature.stats;

import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.settings.TargetRange;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.statistics.PeriodKind;
import app.lightscout.domain.statistics.Statistics;

/** Immutable S-03 display state. */
public record StatisticsUiState(PeriodKind selectedPeriod, Period period, Screen screen,
                                Statistics statistics, TargetRange targetRange,
                                FetchOutcome failure, boolean offlineFromCache) {

    public enum Screen {
        LOADING, CONTENT, EMPTY, PARTIAL, ERROR, OFFLINE
    }
}
