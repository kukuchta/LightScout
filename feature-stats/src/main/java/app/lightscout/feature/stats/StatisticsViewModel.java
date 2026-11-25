package app.lightscout.feature.stats;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.statistics.Period;
import app.lightscout.domain.statistics.PeriodFactory;
import app.lightscout.domain.statistics.PeriodKind;
import app.lightscout.domain.statistics.Statistics;
import app.lightscout.domain.statistics.StatisticsCalculator;
import app.lightscout.usecases.HistoryLoader;
import app.lightscout.usecases.SettingsService;
import app.lightscout.usecases.StatusRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S-03 coordinator. Period selection intentionally remains in this ViewModel only, so a new ViewModel (including
 * after process death or a new screen opening) starts on Yesterday.
 */
public final class StatisticsViewModel extends ViewModel {

    private final Clock clock;
    private final HistoryLoader history;
    private final SettingsService settings;
    private final StatusRepository status;
    private final Executor ioExecutor;
    private final MutableLiveData<StatisticsUiState> state = new MutableLiveData<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private PeriodKind selected = PeriodKind.YESTERDAY;

    public StatisticsViewModel(Clock clock, HistoryLoader history, SettingsService settings,
                               StatusRepository status, Executor ioExecutor) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.history = Objects.requireNonNull(history, "history");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.status = Objects.requireNonNull(status, "status");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        loadSelectedPeriod();
    }

    public LiveData<StatisticsUiState> state() {
        return state;
    }

    public void select(PeriodKind period) {
        Objects.requireNonNull(period, "period");
        if (selected == period) {
            return;
        }
        selected = period;
        loadSelectedPeriod();
    }

    /** GIR-1: repeated Retry taps during an active request are ignored. */
    public void retry() {
        StatisticsUiState current = state.getValue();
        if (current == null || current.screen() != StatisticsUiState.Screen.LOADING) {
            loadSelectedPeriod();
        }
    }

    private void loadSelectedPeriod() {
        long sequence = requestSequence.incrementAndGet();
        PeriodKind requestedKind = selected;
        Period requestedPeriod = makePeriod(requestedKind);
        state.setValue(new StatisticsUiState(requestedKind, requestedPeriod,
                StatisticsUiState.Screen.LOADING, null, null, null, false));
        ioExecutor.execute(() -> {
            if (requestSequence.get() != sequence) {
                return;
            }
            final Settings configured;
            try {
                configured = settings.get();
            } catch (RuntimeException e) {
                postError(sequence, requestedKind, requestedPeriod, FetchOutcome.STORAGE_ERROR);
                return;
            }
            if (requestSequence.get() != sequence) {
                return;
            }
            try {
                history.loadPeriod(requestedPeriod, result -> {
                    if (requestSequence.get() != sequence) {
                        return;
                    }
                    if (!result.ok() || result.value() == null) {
                        FetchOutcome outcome = result.outcome();
                        StatisticsUiState.Screen screen = outcome == FetchOutcome.NO_INTERNET
                                ? StatisticsUiState.Screen.OFFLINE : StatisticsUiState.Screen.ERROR;
                        state.postValue(new StatisticsUiState(requestedKind, requestedPeriod, screen, null,
                                configured.targetRange(), outcome, false));
                        return;
                    }
                    try {
                        List<Reading> readings = result.value();
                        Statistics calculated = new StatisticsCalculator()
                                .compute(readings, requestedPeriod, configured.targetRange());
                        StatisticsUiState.Screen screen = calculated.empty()
                                ? StatisticsUiState.Screen.EMPTY
                                : calculated.partial()
                                        ? StatisticsUiState.Screen.PARTIAL : StatisticsUiState.Screen.CONTENT;
                        boolean servedOffline = !status.current().online();
                        state.postValue(new StatisticsUiState(requestedKind, requestedPeriod, screen, calculated,
                                configured.targetRange(), null, servedOffline));
                    } catch (RuntimeException e) {
                        postError(sequence, requestedKind, requestedPeriod, FetchOutcome.STORAGE_ERROR);
                    }
                });
            } catch (RuntimeException e) {
                postError(sequence, requestedKind, requestedPeriod, FetchOutcome.STORAGE_ERROR);
            }
        });
    }

    private Period makePeriod(PeriodKind kind) {
        return switch (kind) {
            case YESTERDAY -> PeriodFactory.yesterday(
                    ZonedDateTime.ofInstant(clock.now(), clock.zone()));
            case LAST_7_DAYS -> PeriodFactory.lastDays(7, clock.now());
            case LAST_14_DAYS -> PeriodFactory.lastDays(14, clock.now());
            case LAST_30_DAYS -> PeriodFactory.lastDays(30, clock.now());
            case LAST_24_HOURS -> throw new IllegalArgumentException("S-03 does not offer Last 24 hours");
        };
    }

    private void postError(long sequence, PeriodKind kind, Period period, FetchOutcome outcome) {
        if (requestSequence.get() == sequence) {
            state.postValue(new StatisticsUiState(kind, period, StatisticsUiState.Screen.ERROR,
                    null, null, outcome, false));
        }
    }
}
