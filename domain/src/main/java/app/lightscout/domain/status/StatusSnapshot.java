package app.lightscout.domain.status;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.FetchOutcome;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One status projection (03 §6.3, IF-18, CP-00A R-10). Filled by CP-02 {@code StatusRepository}.
 *
 * @param latest                   latest reading excluding future-dated (M-9); nullable
 * @param readingAge               age of {@code latest}; nullable
 * @param nextExpected             {@code latest.time + 5 min}; nullable
 * @param overdue                  next reading overdue
 * @param dataLoss                 IF-02 result
 * @param lastOutcome              outcome of the last tick
 * @param connectionProblem        sticky connection problem; nullable
 * @param lastSuccessfulCheck      nullable
 * @param lastCheckCompleted       nullable
 * @param online                   phone online
 * @param fetching                 a tick is running (GIR-1 Retry)
 * @param configured               onboarding completed (S-12 first use)
 * @param alarmsEnabled            „Alarms” switch
 * @param lowEnabled               Low switch
 * @param highEnabled              High switch
 * @param urgentLowMgdl            Urgent low threshold
 * @param lowMgdl                  Low threshold
 * @param highMgdl                 High threshold
 * @param noDataMinutes            data-loss threshold minutes
 * @param ringing                  ringing alarm type; nullable
 * @param iobCenti                 IOB in centi-units, at most 15 min old (A-11); nullable
 * @param siteStatus               site counter
 * @param sensorStatus             sensor counter
 * @param readinessMissingRequired count of missing required readiness items
 * @param bannerTop                top banner
 * @param computedAt               projection instant
 * @param snoozes                  running snoozes (end instant per type, only those still in the future at
 *                                 {@code computedAt}); unmodifiable (D-128)
 * @param deltaMgdl                glucose change to the previous reading (4–6 min gap), nullable
 * @param statisticsCardOnHome     the statistics card is shown on the home screen (setting)
 * @param checkCycle               the cycle between the last and the next planned check (Home progress bar, D-151);
 *                                 nullable until a check has completed
 */
public record StatusSnapshot(Reading latest, Duration readingAge, Instant nextExpected,
                             boolean overdue, DataLossStatus dataLoss,
                             FetchOutcome lastOutcome, FetchOutcome connectionProblem,
                             Instant lastSuccessfulCheck, Instant lastCheckCompleted,
                             boolean online, boolean fetching,
                             boolean configured,
                             boolean alarmsEnabled, boolean lowEnabled, boolean highEnabled,
                             int urgentLowMgdl, int lowMgdl, int highMgdl, int noDataMinutes,
                             AlarmType ringing, Integer iobCenti,
                             ConsumableStatus siteStatus, ConsumableStatus sensorStatus,
                             int readinessMissingRequired, Banner bannerTop, Instant computedAt,
                             Map<AlarmType, Instant> snoozes, Integer deltaMgdl, boolean statisticsCardOnHome,
                             CheckCycle checkCycle) {

    /**
     * Creates a snapshot.
     *
     * @throws NullPointerException if a non-nullable component is null
     */
    public StatusSnapshot {
        Objects.requireNonNull(dataLoss, "dataLoss");
        Objects.requireNonNull(lastOutcome, "lastOutcome");
        Objects.requireNonNull(siteStatus, "siteStatus");
        Objects.requireNonNull(sensorStatus, "sensorStatus");
        Objects.requireNonNull(bannerTop, "bannerTop");
        Objects.requireNonNull(computedAt, "computedAt");
        snoozes = snoozes == null ? Map.of() : Map.copyOf(snoozes);
    }

    /**
     * Creates a snapshot without a check cycle.
     */
    public StatusSnapshot(Reading latest, Duration readingAge, Instant nextExpected,
                          boolean overdue, DataLossStatus dataLoss,
                          FetchOutcome lastOutcome, FetchOutcome connectionProblem,
                          Instant lastSuccessfulCheck, Instant lastCheckCompleted,
                          boolean online, boolean fetching,
                          boolean configured,
                          boolean alarmsEnabled, boolean lowEnabled, boolean highEnabled,
                          int urgentLowMgdl, int lowMgdl, int highMgdl, int noDataMinutes,
                          AlarmType ringing, Integer iobCenti,
                          ConsumableStatus siteStatus, ConsumableStatus sensorStatus,
                          int readinessMissingRequired, Banner bannerTop, Instant computedAt,
                          Map<AlarmType, Instant> snoozes, Integer deltaMgdl, boolean statisticsCardOnHome) {
        this(latest, readingAge, nextExpected, overdue, dataLoss, lastOutcome, connectionProblem,
                lastSuccessfulCheck, lastCheckCompleted, online, fetching, configured, alarmsEnabled, lowEnabled,
                highEnabled, urgentLowMgdl, lowMgdl, highMgdl, noDataMinutes, ringing, iobCenti, siteStatus,
                sensorStatus, readinessMissingRequired, bannerTop, computedAt, snoozes, deltaMgdl,
                statisticsCardOnHome, null);
    }

    /**
     * Creates a snapshot with the statistics card shown on the home screen.
     */
    public StatusSnapshot(Reading latest, Duration readingAge, Instant nextExpected,
                          boolean overdue, DataLossStatus dataLoss,
                          FetchOutcome lastOutcome, FetchOutcome connectionProblem,
                          Instant lastSuccessfulCheck, Instant lastCheckCompleted,
                          boolean online, boolean fetching,
                          boolean configured,
                          boolean alarmsEnabled, boolean lowEnabled, boolean highEnabled,
                          int urgentLowMgdl, int lowMgdl, int highMgdl, int noDataMinutes,
                          AlarmType ringing, Integer iobCenti,
                          ConsumableStatus siteStatus, ConsumableStatus sensorStatus,
                          int readinessMissingRequired, Banner bannerTop, Instant computedAt,
                          Map<AlarmType, Instant> snoozes, Integer deltaMgdl) {
        this(latest, readingAge, nextExpected, overdue, dataLoss, lastOutcome, connectionProblem,
                lastSuccessfulCheck, lastCheckCompleted, online, fetching, configured, alarmsEnabled, lowEnabled,
                highEnabled, urgentLowMgdl, lowMgdl, highMgdl, noDataMinutes, ringing, iobCenti, siteStatus,
                sensorStatus, readinessMissingRequired, bannerTop, computedAt, snoozes, deltaMgdl, true);
    }

    /**
     * Creates a snapshot without a delta.
     */
    public StatusSnapshot(Reading latest, Duration readingAge, Instant nextExpected,
                          boolean overdue, DataLossStatus dataLoss,
                          FetchOutcome lastOutcome, FetchOutcome connectionProblem,
                          Instant lastSuccessfulCheck, Instant lastCheckCompleted,
                          boolean online, boolean fetching,
                          boolean configured,
                          boolean alarmsEnabled, boolean lowEnabled, boolean highEnabled,
                          int urgentLowMgdl, int lowMgdl, int highMgdl, int noDataMinutes,
                          AlarmType ringing, Integer iobCenti,
                          ConsumableStatus siteStatus, ConsumableStatus sensorStatus,
                          int readinessMissingRequired, Banner bannerTop, Instant computedAt,
                          Map<AlarmType, Instant> snoozes) {
        this(latest, readingAge, nextExpected, overdue, dataLoss, lastOutcome, connectionProblem,
                lastSuccessfulCheck, lastCheckCompleted, online, fetching, configured, alarmsEnabled, lowEnabled,
                highEnabled, urgentLowMgdl, lowMgdl, highMgdl, noDataMinutes, ringing, iobCenti, siteStatus,
                sensorStatus, readinessMissingRequired, bannerTop, computedAt, snoozes, null, true);
    }

    /**
     * Creates a snapshot without running snoozes.
     */
    public StatusSnapshot(Reading latest, Duration readingAge, Instant nextExpected,
                          boolean overdue, DataLossStatus dataLoss,
                          FetchOutcome lastOutcome, FetchOutcome connectionProblem,
                          Instant lastSuccessfulCheck, Instant lastCheckCompleted,
                          boolean online, boolean fetching,
                          boolean configured,
                          boolean alarmsEnabled, boolean lowEnabled, boolean highEnabled,
                          int urgentLowMgdl, int lowMgdl, int highMgdl, int noDataMinutes,
                          AlarmType ringing, Integer iobCenti,
                          ConsumableStatus siteStatus, ConsumableStatus sensorStatus,
                          int readinessMissingRequired, Banner bannerTop, Instant computedAt) {
        this(latest, readingAge, nextExpected, overdue, dataLoss, lastOutcome, connectionProblem,
                lastSuccessfulCheck, lastCheckCompleted, online, fetching, configured, alarmsEnabled, lowEnabled,
                highEnabled, urgentLowMgdl, lowMgdl, highMgdl, noDataMinutes, ringing, iobCenti, siteStatus,
                sensorStatus, readinessMissingRequired, bannerTop, computedAt, Map.of(), null, true);
    }
}
