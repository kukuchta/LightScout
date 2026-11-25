package app.lightscout.feature.home;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.statistics.Statistics;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.feature.home.HomeUiState.BannerAction;
import app.lightscout.feature.home.HomeUiState.BannerKind;
import app.lightscout.feature.home.HomeUiState.BannerRole;
import app.lightscout.feature.home.HomeUiState.BannerUi;
import app.lightscout.feature.home.HomeUiState.EmptyMessage;
import app.lightscout.feature.home.HomeUiState.Progress;
import app.lightscout.feature.home.HomeUiState.ProgressMode;
import app.lightscout.feature.home.HomeUiState.RangeLabel;
import app.lightscout.ui.core.SingleActionGuard;
import app.lightscout.usecases.AlarmActions;
import app.lightscout.usecases.StatusRepository;
import app.lightscout.usecases.status.SurfaceKeys;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S-01 Home logic (CMP-70; CP-12 §7.2, §7.4–§7.7, §7.10; CP-00A R-76): display mapping of IF-21
 * {@link StatusSnapshot} plus the banner and toolbar actions. Pure Java (no Android types) so every rule is
 * JVM-tested. Main thread only.
 *
 * <p>ADR-023 / ADR-025: no business rules beyond display mapping — the banner priority ({@code bannerTop}) and the
 * banner text key (CP-02 {@link SurfaceKeys}) come from the use-case layer; this class only maps them to copy, role,
 * {n} and action. „Turn on” calls IF-25, „Retry” calls IF-21 {@code retry()} (R-25), navigation goes out as
 * {@link HomeCommand}s for IF-29. GIR-1: navigation actions share one guard, released on resume, or on the next
 * frame / after a short fail-safe when no transition started (IR-101); „Retry” is disabled from the tap until the
 * requested tick shows up as {@code snapshot.fetching} or a newer completed tick arrives, then while it is fetching
 * (60 s fail-safe timer, IR-102); „Turn on” until the next snapshot.
 */
public final class HomeModel {

    /** §7.6 „No readings in the last 24 hours”. */
    static final Duration NO_READINGS_WINDOW = Duration.ofHours(24);
    /** Reading cadence when {@code nextExpected} is missing (CP-12 §7.7: 5 min). */
    static final Duration READING_INTERVAL = Duration.ofMinutes(5);
    /** Fail-safe: a „Retry” tap whose tick never shows up is released after this time (no dead button; IR-102). */
    static final long RETRY_FAIL_SAFE_MS = 60_000L;
    /** Fail-safe: a navigation that never pauses S-01 releases the navigation guard after this time (IR-101). */
    static final long NAVIGATION_FAIL_SAFE_MS = 500L;
    /** Refresh period when no reading anchors the minute boundary (age and banner {n} are whole minutes). */
    static final long MINUTE_MS = 60_000L;

    static final Map<String, BannerKind> BANNER_KINDS = Map.ofEntries(
            Map.entry(SurfaceKeys.S01_ALARMS_OFF, BannerKind.ALARMS_OFF),
            Map.entry(SurfaceKeys.S01_LOW_ALARM_OFF, BannerKind.LOW_ALARM_OFF),
            Map.entry(SurfaceKeys.S01_HIGH_ALARM_OFF, BannerKind.HIGH_ALARM_OFF),
            Map.entry(SurfaceKeys.S01_LOW_HIGH_ALARMS_OFF, BannerKind.LOW_HIGH_ALARMS_OFF),
            Map.entry(SurfaceKeys.S01_PERMISSION_MISSING, BannerKind.PERMISSION_MISSING),
            Map.entry(SurfaceKeys.S01_ERROR_NS_NOT_RESPONDING, BannerKind.NS_NOT_RESPONDING),
            Map.entry(SurfaceKeys.S01_ERROR_TOKEN_REJECTED, BannerKind.TOKEN_REJECTED),
            Map.entry(SurfaceKeys.S01_ERROR_TOKEN_PERMISSION_READ, BannerKind.TOKEN_PERMISSION_READ),
            Map.entry(SurfaceKeys.S01_ERROR_TOKEN_PERMISSION_WRITE, BannerKind.TOKEN_PERMISSION_WRITE),
            Map.entry(SurfaceKeys.S01_ERROR_NOT_NIGHTSCOUT, BannerKind.NOT_NIGHTSCOUT),
            Map.entry(SurfaceKeys.S01_ERROR_VERSION_TOO_OLD, BannerKind.VERSION_TOO_OLD),
            Map.entry(SurfaceKeys.S01_ERROR_DATA_LOSS, BannerKind.DATA_LOSS),
            Map.entry(SurfaceKeys.S01_OFFLINE, BannerKind.OFFLINE));

    private static final Map<String, BannerAction> BANNER_ACTIONS = Map.of(
            SurfaceKeys.ACTION_TURN_ON, BannerAction.TURN_ON,
            SurfaceKeys.ACTION_FIX, BannerAction.FIX,
            SurfaceKeys.ACTION_RETRY, BannerAction.RETRY,
            SurfaceKeys.ACTION_FIX_CONNECTION, BannerAction.FIX_CONNECTION);

    /** Receives state and one-time commands. */
    public interface Listener {

        /**
         * A new display state.
         *
         * @param state the state
         */
        void onState(HomeUiState state);

        /**
         * A navigation request (deliver once).
         *
         * @param command the command
         */
        void onCommand(HomeCommand command);
    }

    /** Delayed main-thread tasks (a main-looper {@code Handler} in the app, a manual fake in JVM tests). */
    public interface Timer {

        /**
         * Runs a task once after a delay on the main thread.
         *
         * @param task    the task
         * @param delayMs delay in milliseconds
         */
        void postDelayed(Runnable task, long delayMs);

        /**
         * Cancels a pending task (no-op if not pending).
         *
         * @param task the task
         */
        void cancel(Runnable task);
    }

    private final StatusRepository status;
    private final AlarmActions alarms;
    private final Clock clock;
    private final Timer timer;
    private final Instant createdAt;
    private final Runnable navigationFailSafe = this::onNavigationIdle;
    private final Runnable retryFailSafe = this::releaseRetry;
    private final SingleActionGuard navigationGuard = new SingleActionGuard();
    private Listener listener;
    private StatusSnapshot snapshot;
    private HomeUiState state;
    private boolean loadingDone;
    private boolean retryPending;
    private Instant retryBaselineCompleted;
    private boolean turnOnPending;
    private boolean resumed;
    private Statistics statistics;
    private boolean statisticsLoaded;
    private String deltaReadingId;
    private Instant deltaReadingTime;
    private Integer deltaReadingSgv;
    private Integer deltaMgdl;
    private String pendingEntryIdentifier;
    private FreshnessPresentation.Phase announcedFreshness;

    /**
     * Creates the model; the creation instant starts the C-S01-05 loading period (CP-00A R-76).
     *
     * @param status IF-21
     * @param alarms IF-25
     * @param clock  IF-15
     * @param timer  main-thread delays for the GIR-1 fail-safes
     */
    public HomeModel(StatusRepository status, AlarmActions alarms, Clock clock, Timer timer) {
        this.status = Objects.requireNonNull(status, "status");
        this.alarms = Objects.requireNonNull(alarms, "alarms");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.createdAt = clock.now();
    }

    /**
     * Sets or clears the listener; a non-null listener immediately receives the current state, if any.
     *
     * @param l the listener, or {@code null}
     */
    public void setListener(Listener l) {
        listener = l;
        if (l != null && state != null) {
            l.onState(state);
        }
    }

    /**
     * Returns the last published state.
     *
     * @return the state, {@code null} before the first snapshot
     */
    public HomeUiState state() {
        return state;
    }

    /** Consumed by the view, retained by the model across view recreation; overdue minutes are never live text. */
    FreshnessPresentation.Phase takeFreshnessAnnouncement() {
        if (state == null) {
            return null;
        }
        FreshnessPresentation.Phase phase = FreshnessPresentation.from(state).phase();
        if (phase == FreshnessPresentation.Phase.WAITING || phase == FreshnessPresentation.Phase.MISSED) {
            phase = FreshnessPresentation.Phase.FRESH;   // colour and pace change silently, never announced
        }
        if (phase == announcedFreshness) {
            return null;
        }
        boolean first = announcedFreshness == null;
        announcedFreshness = phase;
        return first && (phase == FreshnessPresentation.Phase.FRESH || phase == FreshnessPresentation.Phase.EMPTY)
                ? null : phase;
    }

    /**
     * A new IF-21 snapshot (initial {@code current()} or a listener call posted to the main thread).
     *
     * @param s the snapshot
     */
    public void onSnapshot(StatusSnapshot s) {
        Reading latest = s.latest();
        if (!sameReading(deltaReadingId, deltaReadingTime, deltaReadingSgv, latest)) {
            deltaReadingId = latest == null ? null : latest.id();
            deltaReadingTime = latest == null ? null : latest.time();
            deltaReadingSgv = latest == null ? null : latest.sgv();
            deltaMgdl = null;
        }
        snapshot = Objects.requireNonNull(s, "snapshot");
        Instant completed = s.lastCheckCompleted();
        if (!loadingDone && completed != null && !completed.isBefore(createdAt)) {
            loadingDone = true;
        }
        // GIR-1: once the requested tick shows up as fetching — or a newer completed tick arrives, because postValue
        // may coalesce fetching true → false (IR-102) — the button follows !snapshot.fetching only.
        if (retryPending && (s.fetching() || completedAfter(completed, retryBaselineCompleted))) {
            releaseRetry();
        }
        turnOnPending = false;
        publish();
    }

    /** Applies a cache-only delta only while its authoritative reading is still current. */
    public void onDelta(String readingId, Instant readingTime, int readingSgv, Integer value) {
        Reading latest = snapshot == null ? null : snapshot.latest();
        if (sameReading(readingId, readingTime, readingSgv, latest)) {
            deltaMgdl = value;
            publish();
        }
    }

    /** Clears a cached delta after a server/cache epoch reset. */
    public void invalidateDelta() {
        deltaMgdl = null;
        publish();
    }

    private static boolean sameReading(String id, Instant time, Integer sgv, Reading reading) {
        return reading != null && Objects.equals(id, reading.id()) && Objects.equals(time, reading.time())
                && Objects.equals(sgv, reading.sgv());
    }

    /**
     * Minute refresh while the screen is started (ADR-049): age, progress and banner {n} follow the clock.
     */
    public void onMinute() {
        publish();
    }

    /** Publishes the latest 24-hour statistics result from IF-22 / IF-04. */
    public void onStatistics(Statistics value) {
        statistics = value;
        statisticsLoaded = true;
        publish();
    }

    /** Shows the doughnut's loading state while its off-main-thread data is being fetched. */
    public void onStatisticsLoading() {
        statistics = null;
        statisticsLoaded = false;
        publish();
    }

    /** Clears the statistics loading state when a request cannot be completed. */
    public void onStatisticsUnavailable() {
        statistics = null;
        statisticsLoaded = true;
        publish();
    }

    /**
     * S-01 resumed: releases the navigation guard (the user is back) and asks IF-21 to re-project without network,
     * which re-checks readiness (F15, CP-00A R-25 / R-76).
     */
    public void onResume() {
        resumed = true;
        releaseNavigation();
        status.refresh();
        publish();
    }

    /** S-01 paused (a transition started, or the app went to the background). */
    public void onPause() {
        resumed = false;
    }

    /**
     * The frame after a navigation request: S-01 is still resumed, so no transition started (e.g. an M1 no-op
     * destination or a failed navigate) and the navigation guard is released (GIR-1, IR-101). Also the
     * {@link #NAVIGATION_FAIL_SAFE_MS} fail-safe. No effect while paused.
     */
    public void onNavigationIdle() {
        if (resumed) {
            releaseNavigation();
        }
    }

    /** Cancels pending fail-safes ({@code ViewModel.onCleared}). */
    public void dispose() {
        timer.cancel(navigationFailSafe);
        timer.cancel(retryFailSafe);
    }

    /** C-S01-03 „Statistics” (A-S01-3). */
    public void onStatistics() {
        navigate(HomeCommand.OPEN_STATISTICS);
    }

    /** C-S01-04 „Settings” (A-S01-4). */
    public void onSettings() {
        navigate(HomeCommand.OPEN_SETTINGS);
    }

    /** Explicit dock/counter create route, sharing the existing navigation guard. */
    public void onNewEntry(EntryType type) {
        navigate(switch (Objects.requireNonNull(type, "type")) {
            case CARBS -> HomeCommand.ADD_CARBS;
            case BOLUS -> HomeCommand.ADD_BOLUS;
            case BG_CHECK -> HomeCommand.ADD_BG;
            case SITE_CHANGE -> HomeCommand.ADD_SITE;
            case SENSOR_CHANGE -> HomeCommand.ADD_SENSOR;
            default -> throw new IllegalArgumentException("Not a loggable entry type");
        });
    }

    /** Opens the selected chart entry through IF-29 with the same single-action guard as the FAB. */
    public void onEditEntry(String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            pendingEntryIdentifier = identifier;
            navigate(HomeCommand.EDIT_ENTRY);
        }
    }

    /** Consumes the identifier paired with the one-shot edit command. */
    public String takePendingEntryIdentifier() {
        String identifier = pendingEntryIdentifier;
        pendingEntryIdentifier = null;
        return identifier;
    }

    /** C-S01-06 banner action tapped (A-S01-9). */
    public void onBannerAction() {
        if (state != null) {
            act(state.banner());
        }
    }

    private void act(BannerUi banner) {
        if (snapshot == null || banner == null || !banner.actionEnabled()) {
            return;
        }

        switch (banner.action()) {
            case TURN_ON:
                turnOnPending = true;
                turnOn(snapshot);
                publish();
                break;
            case FIX:
                navigate(HomeCommand.OPEN_READINESS_CHECK);
                break;
            case RETRY:
                retryPending = true;
                retryBaselineCompleted = snapshot.lastCheckCompleted();
                timer.cancel(retryFailSafe);
                timer.postDelayed(retryFailSafe, RETRY_FAIL_SAFE_MS);
                status.retry();
                publish();
                break;
            case FIX_CONNECTION:
                navigate(HomeCommand.OPEN_CONNECTION_EDIT);
                break;
            case NONE:
            default:
                break;
        }
    }

    /** A popup must never activate an action belonging to an obsolete cause. */
    public void onBannerAction(BannerKind displayedKind) {
        if (state == null) {
            return;
        }
        for (BannerUi b : state.banners()) {
            if (b.kind() == displayedKind) {
                act(b);
                return;
            }
        }
    }

    /**
     * Milliseconds until the next whole minute of the reading age, when the visible age text changes (1 s minimum,
     * one minute without a reading).
     *
     * @return the delay for the next {@link #onMinute()}
     */
    public long nextRefreshDelayMs() {
        Reading latest = snapshot == null ? null : snapshot.latest();
        if (latest == null) {
            return MINUTE_MS;
        }
        long elapsed = Duration.between(latest.time(), clock.now()).toMillis();
        long delay = elapsed < 0 ? Math.min(-elapsed, MINUTE_MS) : MINUTE_MS - (elapsed % MINUTE_MS);
        return Math.max(1_000L, delay);
    }

    private void navigate(HomeCommand command) {
        if (navigationGuard.tryStart() && listener != null) {
            timer.postDelayed(navigationFailSafe, NAVIGATION_FAIL_SAFE_MS);
            listener.onCommand(command);
        }
    }

    private void releaseNavigation() {
        timer.cancel(navigationFailSafe);
        navigationGuard.complete();
    }

    private void releaseRetry() {
        timer.cancel(retryFailSafe);
        if (retryPending) {
            retryPending = false;
            retryBaselineCompleted = null;
            publish();
        }
    }

    private static boolean completedAfter(Instant completed, Instant baseline) {
        return completed != null && (baseline == null || completed.isAfter(baseline));
    }

    private void turnOn(StatusSnapshot s) {
        if (!s.alarmsEnabled()) {
            alarms.setAlarmsEnabled(true);
            return;
        }
        if (!s.lowEnabled()) {
            alarms.setAlarmTypeEnabled(AlarmType.LOW, true);
        }
        if (!s.highEnabled()) {
            alarms.setAlarmTypeEnabled(AlarmType.HIGH, true);
        }
    }

    private void publish() {
        if (snapshot == null) {
            return;
        }
        state = map(snapshot, clock.now(), clock.zone(), !loadingDone, retryPending, turnOnPending,
                statistics, statisticsLoaded, deltaMgdl);
        if (listener != null) {
            listener.onState(state);
        }
    }

    /**
     * Pure snapshot → display mapping (CP-12 §7.2, §7.5–§7.7; CP-00A R-76).
     *
     * @param s             the snapshot
     * @param now           IF-15 now
     * @param loading       C-S01-05 visible
     * @param retryPending  a „Retry” tap is not yet acknowledged
     * @param turnOnPending a „Turn on” tap is not yet reflected in a snapshot
     * @return the state
     */
    static HomeUiState map(StatusSnapshot s, Instant now, java.time.ZoneId zone, boolean loading, boolean retryPending,
                           boolean turnOnPending, Statistics statistics, boolean statisticsLoaded,
                           Integer deltaMgdl) {
        Reading latest = s.latest();
        Duration age = latest == null ? null : nonNegative(Duration.between(latest.time(), now));
        boolean stale = age != null && age.compareTo(Duration.ofMinutes(s.noDataMinutes())) >= 0;
        return new HomeUiState(loading,
                banner(s, now, retryPending, turnOnPending),
                banners(s, now, retryPending, turnOnPending),
                latest == null ? null : latest.sgv(),
                latest == null ? RangeLabel.NONE : rangeLabel(latest.sgv(), s),
                latest == null ? null : latest.trend(),
                deltaMgdl,
                age,
                stale,
                s.iobCenti() == null ? null : iobText(s.iobCenti()),
                progress(s, now),
                emptyMessage(s, age), s, CounterPresentation.from(s.siteStatus(), now),
                CounterPresentation.from(s.sensorStatus(), now), now, zone, statistics, statisticsLoaded);
    }

    /**
     * Range label by AL-1 comparisons, regardless of the Low / High switches (CP-00A R-76).
     *
     * @param sgv reading in mg/dL
     * @param s   snapshot thresholds
     * @return the label
     */
    static RangeLabel rangeLabel(int sgv, StatusSnapshot s) {
        if (sgv <= s.urgentLowMgdl()) {
            return RangeLabel.URGENT_LOW;
        }
        if (sgv <= s.lowMgdl()) {
            return RangeLabel.LOW;
        }
        if (sgv >= s.highMgdl()) {
            return RangeLabel.HIGH;
        }
        return RangeLabel.NONE;
    }

    /**
     * IOB „x.x” with one decimal, half-up, „.” as separator like the other insulin values (R-82).
     *
     * @param centi IOB in centi-units
     * @return the number text
     */
    static String iobText(int centi) {
        return BigDecimal.valueOf(centi, 2).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static Progress progress(StatusSnapshot s, Instant now) {
        Reading latest = s.latest();
        if (latest == null) {
            return Progress.HIDDEN;
        }
        app.lightscout.domain.status.CheckCycle cycle = s.checkCycle();
        if (cycle != null) {
            // D-151: the bar runs between two checks (last completed to next planned), not from the reading time.
            long span = Math.max(1_000L, Duration.between(cycle.start(), cycle.next()).toMillis());
            long elapsed = Math.max(0L, Math.min(span, Duration.between(cycle.start(), now).toMillis()));
            return new Progress(ProgressMode.DETERMINATE, (int) Math.min(100L, elapsed * 100L / span), elapsed, span,
                    cycle.kind());
        }
        Instant next = s.nextExpected() != null ? s.nextExpected() : latest.time().plus(READING_INTERVAL);
        if (s.overdue() || now.isAfter(next)) {
            return Progress.OVERDUE;
        }
        long span = Math.max(1L, Duration.between(latest.time(), next).toMillis());
        long elapsed = Math.max(0L, Duration.between(latest.time(), now).toMillis());
        int percent = (int) Math.min(100L, elapsed * 100L / span);
        return new Progress(ProgressMode.DETERMINATE, percent, elapsed, span);
    }

    private static EmptyMessage emptyMessage(StatusSnapshot s, Duration age) {
        if (s.latest() == null && s.dataLoss().kind() == DataLossKind.FIRST_USE) {
            return EmptyMessage.FIRST_USE;
        }
        if (age == null || age.compareTo(NO_READINGS_WINDOW) >= 0) {
            return EmptyMessage.NO_READINGS_24H;
        }
        return EmptyMessage.NONE;
    }

    private static BannerUi banner(StatusSnapshot s, Instant now, boolean retryPending, boolean turnOnPending) {
        SurfaceKeys.SurfaceCodes codes = SurfaceKeys.of(s);
        return bannerUi(codes.s01Banner(), codes.s01Action(), s, now, retryPending, turnOnPending);
    }

    private static List<BannerUi> banners(StatusSnapshot s, Instant now, boolean retryPending,
                                          boolean turnOnPending) {
        List<BannerUi> out = new ArrayList<>();
        for (SurfaceKeys.Fault f : SurfaceKeys.faults(s)) {
            BannerUi ui = bannerUi(f.banner(), f.action(), s, now, retryPending, turnOnPending);
            if (ui != null) {
                out.add(ui);
            }
        }
        return out;
    }

    private static BannerUi bannerUi(String bannerKey, String actionKey, StatusSnapshot s, Instant now,
                                     boolean retryPending, boolean turnOnPending) {
        BannerKind kind = bannerKey == null ? null : BANNER_KINDS.get(bannerKey);
        if (kind == null) {
            return null;
        }
        if (kind == BannerKind.OFFLINE && s.latest() == null) {
            return null;   // R-76: the offline banner needs data to show its age
        }
        BannerAction action = actionKey == null ? BannerAction.NONE
                : BANNER_ACTIONS.getOrDefault(actionKey, BannerAction.NONE);
        boolean enabled;
        switch (action) {
            case RETRY:
                enabled = !s.fetching() && !retryPending;
                break;
            case TURN_ON:
                enabled = !turnOnPending;
                break;
            default:
                enabled = true;
                break;
        }
        return new BannerUi(kind, minutes(kind, s, now), action, role(kind), enabled);
    }

    private static long minutes(BannerKind kind, StatusSnapshot s, Instant now) {
        if (kind == BannerKind.OFFLINE) {
            return nonNegative(Duration.between(s.latest().time(), now)).toMinutes();
        }
        if (kind == BannerKind.DATA_LOSS) {
            Instant anchor = s.dataLoss().since() != null ? s.dataLoss().since()
                    : s.latest() != null ? s.latest().time() : null;
            return anchor == null ? s.noDataMinutes() : nonNegative(Duration.between(anchor, now)).toMinutes();
        }
        return 0;
    }

    private static BannerRole role(BannerKind kind) {
        switch (kind) {
            case ALARMS_OFF:
            case LOW_ALARM_OFF:
            case HIGH_ALARM_OFF:
            case LOW_HIGH_ALARMS_OFF:
                return BannerRole.TERTIARY;
            case OFFLINE:
                return BannerRole.SECONDARY;
            default:
                return BannerRole.ERROR;
        }
    }

    private static Duration nonNegative(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }
}
