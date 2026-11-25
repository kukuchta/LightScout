package app.lightscout.usecases.status;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.consumable.ConsumableCalculator;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.consumable.ConsumableStatus;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.dataloss.DataLossClassifier;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.dataloss.DataLossStatus;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.monitor.CollectionCursor;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.AlarmStateStore;
import app.lightscout.domain.port.AppFlagsStore;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorStateStore;
import app.lightscout.domain.port.ReadinessSource;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.SettingsStore;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.settings.Settings;
import app.lightscout.domain.status.Banner;
import app.lightscout.domain.status.CheckCycle;
import app.lightscout.domain.status.ReadingDelta;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import app.lightscout.usecases.common.MonitorStateMemory;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the single {@link StatusSnapshot} (CMP-11 projection; CP-02 §8.3, ADR-025, CP-00A R-10, R-25, R-76).
 * Runs on {@code monitorExecutor}. Store failures never stop a projection: a failed read falls back to the
 * „absent” value (SR-37).
 */
public final class StatusProjector {

    /** F-04: next reading expected 5 min after the latest one. */
    public static final Duration READING_INTERVAL = Duration.ofMinutes(5);
    /** DCR-45: how long after a new connection a missing change entry may show „Loading…” instead of „No records”. */
    static final Duration HISTORY_LOADING_MAX = Duration.ofMinutes(10);
    /** Cache window (ADR-030, D-016); same value as {@code ChangeSync.CACHE_WINDOW}. */
    private static final Duration CACHE_WINDOW = Duration.ofDays(31);

    /** A-11: IOB is shown only when the device status is at most 15 min old. */
    public static final Duration IOB_MAX_AGE = Duration.ofMinutes(15);

    private final ReadingStore readings;
    private final EntryStore entries;
    private final DeviceStatusStore deviceStatus;
    private final MonitorStateStore monitorState;
    private final SettingsStore settings;
    private final AlarmStateStore alarmState;
    private final Connectivity connectivity;
    private final ReadinessSource readiness;
    private final AppFlagsStore appFlags;
    private final Clock clock;
    private final AlarmStateMemory alarmMemory;
    private final MonitorStateMemory monitorMemory;
    private final Logger logger;
    /** Last logged readiness count; {@code null} until the first projection in this process (BUG-02 / Q-50). */
    private Integer loggedMissingRequired;
    private final ConsumableCalculator consumables = new ConsumableCalculator();
    private final DataLossClassifier dataLossClassifier = new DataLossClassifier();

    /**
     * Creates the projector.
     *
     * @param readings     IF-08
     * @param entries      IF-09
     * @param deviceStatus IF-10
     * @param monitorState IF-13 (read only here)
     * @param settings     IF-11
     * @param alarmState   IF-14 (read only here)
     * @param connectivity IF-19
     * @param readiness    IF-65
     * @param appFlags     IF-60
     * @param clock         IF-15
     * @param alarmMemory   in-memory ENT-08 (an unsaved dirty state wins over the store — IR-39)
     * @param monitorMemory last authoritative monitor state (used when IF-13 get fails — IR-39)
     * @param logger        IF-66 ({@code READINESS_MISSING:<count>} on change — Q-50)
     */
    public StatusProjector(ReadingStore readings, EntryStore entries, DeviceStatusStore deviceStatus,
                           MonitorStateStore monitorState, SettingsStore settings, AlarmStateStore alarmState,
                           Connectivity connectivity, ReadinessSource readiness, AppFlagsStore appFlags,
                           Clock clock, AlarmStateMemory alarmMemory, MonitorStateMemory monitorMemory,
                           Logger logger) {
        this.readings = readings;
        this.entries = entries;
        this.deviceStatus = deviceStatus;
        this.monitorState = monitorState;
        this.settings = settings;
        this.alarmState = alarmState;
        this.connectivity = connectivity;
        this.readiness = readiness;
        this.appFlags = appFlags;
        this.clock = clock;
        this.alarmMemory = alarmMemory;
        this.monitorMemory = monitorMemory;
        this.logger = logger;
    }

    /**
     * Projects a snapshot from values the caller already holds (tick and re-evaluation path; R-69: post-tick
     * monitor state values computed in memory).
     *
     * @param now      projection instant
     * @param ms       monitor state (post-tick)
     * @param s        settings
     * @param as       alarm state after this evaluation
     * @param latest   M-9-filtered latest reading; nullable
     * @param dataLoss IF-02 result
     * @param fetching a tick is running
     * @return the snapshot
     */
    public StatusSnapshot project(Instant now, MonitorState ms, Settings s, AlarmState as, Reading latest,
                                  DataLossStatus dataLoss, boolean fetching) {
        Duration age = latest == null ? null : Duration.between(latest.time(), now);
        Instant nextExpected = latest == null ? null : latest.time().plus(READING_INTERVAL);
        boolean overdue = nextExpected != null && now.isAfter(nextExpected);
        boolean online = isOnline();
        boolean historyLoading = historyLoading(ms, now);
        int missing = missingRequired();
        logReadinessIfChanged(missing);
        Banner banner = bannerTop(s.alarmsEnabled(), s.lowEnabled(), s.highEnabled(), missing,
                ms.lastOutcome(), ms.connectionProblem(), dataLoss, online, latest);
        return new StatusSnapshot(latest, age, nextExpected, overdue, dataLoss,
                ms.lastOutcome(), ms.connectionProblem(), ms.lastSuccessfulCheck(), ms.lastCheckCompleted(),
                online, fetching, configured(),
                s.alarmsEnabled(), s.lowEnabled(), s.highEnabled(),
                s.urgentLowMgdl(), s.lowMgdl(), s.highMgdl(), s.noDataMinutes(),
                as.activeType(), iob(now),
                consumable(ConsumableKind.SITE, EntryType.SITE_CHANGE, s.siteLifetimeDays(), now, historyLoading),
                consumable(ConsumableKind.SENSOR, EntryType.SENSOR_CHANGE, s.sensorLifetimeDays(), now,
                        historyLoading),
                missing, banner, now, snoozes(as, now), delta(latest), s.statisticsCardOnHome(),
                CheckCycle.of(now, ms.lastCheckCompleted(), ms.lastOutcome(), ms.connectionProblem(),
                        ms.lastReadingTime(), ms.monitoringSince(), ms.arrivalLagMedianSec(),
                        ms.recentLagSec().isEmpty() ? null : ms.recentLagSec().get(ms.recentLagSec().size() - 1),
                        s.dataLossThreshold()));
    }

    private Integer delta(Reading latest) {
        if (latest == null || latest.time() == null) {
            return null;
        }
        List<Reading> previous = readOr(() -> readings.range(latest.time().minus(ReadingDelta.MAX_GAP.plusMinutes(1)),
                latest.time()), List.<Reading>of());
        return ReadingDelta.calculate(latest, previous);
    }

    private static Map<AlarmType, Instant> snoozes(AlarmState as, Instant now) {
        Map<AlarmType, Instant> out = new EnumMap<>(AlarmType.class);
        for (Map.Entry<AlarmType, AlarmTypeState> e : as.perType().entrySet()) {
            Instant until = e.getValue().snoozedUntil();
            if (until != null && until.isAfter(now)) {
                out.put(e.getKey(), until);
            }
        }
        return out;
    }

    /**
     * Re-projects from the stores without network ({@code StatusRepository.refresh()}).
     *
     * @param fetching a tick is running
     * @return the snapshot
     */
    public StatusSnapshot projectFromStores(boolean fetching) {
        Instant now = clock.now();
        MonitorState ms;
        try {
            ms = monitorState.get();
            monitorMemory.set(ms);
        } catch (StorageException e) {
            ms = monitorMemory.get() != null ? monitorMemory.get() : MonitorState.initial();
        }
        Settings s = readOr(settings::get, Settings.defaults());
        AlarmState as = alarmMemory.read(alarmState);
        alarmMemory.takeFailure();   // a refresh is not a tick: no outcome to mark
        Reading latest = readOr(() -> ReadingQueries.latest(readings, now, List.of()), null);
        DataLossStatus dataLoss = dataLossClassifier.classify(now, ms.lastReadingTime(), ms.monitoringSince(),
                ms.lastOutcome(), s.dataLossThreshold());
        return project(now, ms, s, as, latest, dataLoss, fetching);
    }

    /**
     * Snapshot shown before the first projection: first use, defaults, no data.
     *
     * @param now creation instant
     * @return the snapshot
     */
    public static StatusSnapshot initial(Instant now) {
        Settings s = Settings.defaults();
        return new StatusSnapshot(null, null, null, false,
                new DataLossStatus(DataLossKind.FIRST_USE, null, null),
                FetchOutcome.OK, null, null, null, true, false, false,
                s.alarmsEnabled(), s.lowEnabled(), s.highEnabled(),
                s.urgentLowMgdl(), s.lowMgdl(), s.highMgdl(), s.noDataMinutes(),
                null, null,
                new ConsumableStatus(ConsumableKind.SITE, ConsumableState.NONE_FOUND, null, null, null, null),
                new ConsumableStatus(ConsumableKind.SENSOR, ConsumableState.NONE_FOUND, null, null, null, null),
                0, Banner.NONE, now);
    }

    /**
     * Returns a copy with another {@code fetching} flag (GIR-1: S-01 „Retry” disabled while a tick runs).
     *
     * @param s        snapshot
     * @param fetching new flag
     * @return the copy
     */
    public static StatusSnapshot withFetching(StatusSnapshot s, boolean fetching) {
        return new StatusSnapshot(s.latest(), s.readingAge(), s.nextExpected(), s.overdue(), s.dataLoss(),
                s.lastOutcome(), s.connectionProblem(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                s.online(), fetching, s.configured(), s.alarmsEnabled(), s.lowEnabled(), s.highEnabled(),
                s.urgentLowMgdl(), s.lowMgdl(), s.highMgdl(), s.noDataMinutes(), s.ringing(), s.iobCenti(),
                s.siteStatus(), s.sensorStatus(), s.readinessMissingRequired(), s.bannerTop(), s.computedAt(), s.snoozes(),
                s.deltaMgdl(), s.statisticsCardOnHome(), s.checkCycle());
    }

    /**
     * Banner priority ALARMS_OFF &gt; SINGLE_ALARM_OFF &gt; READINESS &gt; CONNECTION_OR_DATA_LOSS &gt; OFFLINE
     * (S-01 C-S01-06, CP-02 §8.3, CP-00A R-76).
     *
     * @param alarmsEnabled     „Alarms” switch
     * @param lowEnabled        Low switch
     * @param highEnabled       High switch
     * @param readinessMissing  missing required readiness items
     * @param lastOutcome       per-tick outcome
     * @param connectionProblem sticky daily-check problem; nullable
     * @param dataLoss          data-loss status
     * @param online            IF-19
     * @param latest            latest reading; nullable
     * @return the top banner
     */
    public static Banner bannerTop(boolean alarmsEnabled, boolean lowEnabled, boolean highEnabled,
                                   int readinessMissing, FetchOutcome lastOutcome, FetchOutcome connectionProblem,
                                   DataLossStatus dataLoss, boolean online, Reading latest) {
        if (!alarmsEnabled) {
            return Banner.ALARMS_OFF;
        }
        if (!lowEnabled || !highEnabled) {
            return Banner.SINGLE_ALARM_OFF;
        }
        if (readinessMissing > 0) {
            return Banner.READINESS;
        }
        if (connectionFault(lastOutcome, connectionProblem, dataLoss, online, latest)) {
            return Banner.CONNECTION_OR_DATA_LOSS;
        }
        if (offlineFault(lastOutcome, online, latest)) {
            return Banner.OFFLINE;
        }
        return Banner.NONE;
    }

    /** Connection / data-loss fault, independent of the banner priority (DCR-21 per-fault chips). */
    static boolean connectionFault(FetchOutcome lastOutcome, FetchOutcome connectionProblem, DataLossStatus dataLoss,
                                   boolean online, Reading latest) {
        boolean uploader = dataLoss.kind() == DataLossKind.DATA_LOSS && dataLoss.cause() == DataLossCause.UPLOADER;
        if (offlineFault(lastOutcome, online, latest)) {
            // The phone is offline: "Server error" and "Uploader" would blame the wrong party; only
            // configuration problems (token, permissions, version, address) still count.
            return connectionProblem != null || isConfigurationOutcome(lastOutcome);
        }
        return connectionProblem != null || isConnectionOutcome(lastOutcome) || uploader;
    }

    private static boolean isConfigurationOutcome(FetchOutcome o) {
        return o == FetchOutcome.NOT_NIGHTSCOUT || o == FetchOutcome.TOKEN_REJECTED
                || o == FetchOutcome.PERMISSION_MISSING || o == FetchOutcome.SERVER_TOO_OLD;
    }

    /** Offline fault, independent of the banner priority (DCR-21 per-fault chips). */
    static boolean offlineFault(FetchOutcome lastOutcome, boolean online, Reading latest) {
        return (!online || lastOutcome == FetchOutcome.NO_INTERNET) && latest != null;
    }

    private static boolean isConnectionOutcome(FetchOutcome o) {
        return o == FetchOutcome.NS_NOT_RESPONDING || o == FetchOutcome.NOT_NIGHTSCOUT
                || o == FetchOutcome.TOKEN_REJECTED || o == FetchOutcome.PERMISSION_MISSING
                || o == FetchOutcome.SERVER_TOO_OLD;
    }

    private Integer iob(Instant now) {
        Optional<DeviceStatus> latest = readOr(deviceStatus::latest, Optional.empty());
        if (latest.isEmpty()) {
            return null;
        }
        DeviceStatus d = latest.get();
        if (d.iobCenti() == null || d.time() == null) {
            return null;
        }
        return Duration.between(d.time(), now).compareTo(IOB_MAX_AGE) <= 0 ? d.iobCenti() : null;
    }

    private ConsumableStatus consumable(ConsumableKind kind, EntryType type, int lifetimeDays, Instant now,
                                        boolean historyLoading) {
        Optional<Entry> change = readOr(() -> entries.latestOfType(type), Optional.empty());
        Instant lastChange = change.map(Entry::time).orElse(null);
        if (lastChange == null && historyLoading) {
            return new ConsumableStatus(kind, ConsumableState.LOADING, null, null, null, null);
        }
        return consumables.status(kind, lastChange, lifetimeDays, now);
    }

    /**
     * DCR-45: true while the first treatments backfill of this connection may still bring a site / sensor change:
     * the connection is younger than {@link #HISTORY_LOADING_MAX} and the treatments cache is not yet complete down
     * to the cache window. Bounded in time, so a backfill that never finishes (BUG-18: server ignoring the window)
     * cannot keep a counter on „Loading…” — it then falls back to „No records”.
     */
    static boolean historyLoading(MonitorState ms, Instant now) {
        Instant since = ms.monitoringSince();
        if (since == null || !now.isBefore(since.plus(HISTORY_LOADING_MAX))) {
            return false;
        }
        CollectionCursor treatments = ms.cursors().get(NsCollection.TREATMENTS);
        Instant completeFrom = treatments == null ? null : treatments.completeFrom();
        return completeFrom == null || completeFrom.isAfter(now.minus(CACHE_WINDOW));
    }

    private boolean isOnline() {
        try {
            return connectivity.isOnline();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * R-13 / Q-50: logs {@code READINESS_MISSING:<count>} on the first projection in the process and whenever the
     * count changes — never on every tick. Detail = integer count (R-13 allow-list). Monitor thread only.
     */
    private void logReadinessIfChanged(int missing) {
        if (loggedMissingRequired != null && loggedMissingRequired == missing) {
            return;
        }
        loggedMissingRequired = missing;
        try {
            logger.event(EventCode.READINESS_MISSING, Integer.toString(missing));
        } catch (RuntimeException e) {
            // logging must never stop a projection (SR-37)
        }
    }

    private int missingRequired() {
        try {
            return Math.max(0, readiness.missingRequiredCount());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean configured() {
        try {
            return appFlags.onboardingCompleted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static <T> T readOr(java.util.function.Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (StorageException e) {
            return fallback;
        }
    }
}
