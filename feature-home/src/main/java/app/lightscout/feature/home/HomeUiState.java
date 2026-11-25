package app.lightscout.feature.home;

import app.lightscout.domain.model.Trend;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.domain.statistics.Statistics;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Display state of S-01 Home (CP-12 §7.2, §7.5, §7.6, §7.7) as produced by {@link HomeModel}: values and enums only,
 * no Android types and no text. {@link HomeFragment} turns it into views with the {@code :ui-core} formatters and
 * the copy table {@link HomeCopy}.
 *
 * @param loading         C-S01-05 indeterminate bar under the app bar (CP-00A R-76)
 * @param banner          C-S01-06 top status banner; {@code null} = no banner
 * @param banners         C-S01-06 fault chips, all active ones in display order (DCR-21); empty = none; the first
 *                        equals {@code banner}
 * @param sgv             C-S01-07 current reading in mg/dL; {@code null} = „—”
 * @param rangeLabel      C-S01-08 range label
 * @param trend           C-S01-09 trend; {@code null} = no reading (no icon)
 * @param deltaMgdl       current minus immediate distinct-time cached predecessor; {@code null} = unknown
 * @param age             C-S01-10 reading age; {@code null} = „No readings”
 * @param stale           reading age ≥ data-loss threshold: reading greyed, age in error role (§7.5 error extras)
 * @param iob             C-S01-11 text value „x.x”; {@code null} = row hidden (DS:D-008, A-11)
 * @param progress        C-S01-12 next-reading indicator
 * @param emptyMessage    empty-state message shown in the chart area (§7.6 Empty rows; CP-00A R-77)
 */
public record HomeUiState(boolean loading, BannerUi banner, List<BannerUi> banners, Integer sgv, RangeLabel rangeLabel,
                          Trend trend,
                          Integer deltaMgdl, Duration age, boolean stale, String iob, Progress progress,
                          EmptyMessage emptyMessage,
                          StatusSnapshot snapshot, CounterPresentation siteCounter,
                          CounterPresentation sensorCounter, Instant now, ZoneId zone, Statistics statistics,
                          boolean statisticsLoaded) {

    /**
     * Validates the non-null components.
     *
     * @throws NullPointerException if a required component is null
     */
    public HomeUiState {
        Objects.requireNonNull(rangeLabel, "rangeLabel");
        banners = List.copyOf(Objects.requireNonNull(banners, "banners"));
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(emptyMessage, "emptyMessage");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(siteCounter, "siteCounter");
        Objects.requireNonNull(sensorCounter, "sensorCounter");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
    }

    /** C-S01-08 range label (CP-00A R-76: AL-1 comparisons, shown regardless of the Low / High switches). */
    public enum RangeLabel {
        /** In range: visible „In range” chip when a reading exists. */
        NONE,
        /** {@code sgv ≤ urgentLow}: „URGENT LOW”, {@code errorContainer} filled badge. */
        URGENT_LOW,
        /** {@code sgv ≤ low}: „LOW”, {@code error}. */
        LOW,
        /**
         * {@code sgv ≥ high}: „HIGH”, {@code warning} text colour — also above 250 (Q-89: the GIR-10 very-high colour
         * is for drawn areas only).
         */
        HIGH
    }

    /** C-S01-12 mode. */
    public enum ProgressMode {
        /** No reading: indicator hidden. */
        HIDDEN,
        /** Determinate 0–100 % over 5 min from the latest reading. */
        DETERMINATE,
        /** Overdue: indeterminate + „Waiting for reading…”. */
        OVERDUE
    }

    /**
     * C-S01-12 next-reading indicator.
     *
     * @param mode    mode
     * @param percent   0–100 for {@link ProgressMode#DETERMINATE}, else 0
     * @param elapsedMs time since the latest reading when the state was built (DETERMINATE), else 0
     * @param spanMs    last check to the next planned check (DETERMINATE), else 0; the view fills the bar between
     *                  states
     * @param kind      how the check cycle reads (D-151); {@code null} without a cycle
     */
    public record Progress(ProgressMode mode, int percent, long elapsedMs, long spanMs,
                           app.lightscout.domain.status.CheckCycle.Kind kind) {

        /**
         * Progress without a cycle kind (tests, fallbacks).
         *
         * @param mode      mode
         * @param percent   0–100 for DETERMINATE, else 0
         * @param elapsedMs elapsed milliseconds
         * @param spanMs    span milliseconds
         */
        public Progress(ProgressMode mode, int percent, long elapsedMs, long spanMs) {
            this(mode, percent, elapsedMs, spanMs, null);
        }

        /**
         * Progress without the smooth-fill timing (hidden / overdue / tests).
         *
         * @param mode    mode
         * @param percent 0–100 for DETERMINATE, else 0
         */
        public Progress(ProgressMode mode, int percent) {
            this(mode, percent, 0L, 0L);
        }

        /** Hidden indicator. */
        public static final Progress HIDDEN = new Progress(ProgressMode.HIDDEN, 0);
        /** Overdue indicator. */
        public static final Progress OVERDUE = new Progress(ProgressMode.OVERDUE, 0);

        /**
         * Validates the mode.
         *
         * @throws NullPointerException if {@code mode} is null
         */
        public Progress {
            Objects.requireNonNull(mode, "mode");
        }
    }

    /** Empty-state message in the chart area (§7.6; CP-00A R-77 — CP-12b passes it to the chart). */
    public enum EmptyMessage {
        /** Readings exist: no message. */
        NONE,
        /** Empty — first use: „Waiting for the first reading from Nightscout…”. */
        FIRST_USE,
        /** Empty — no results: „No readings in the last 24 hours”. */
        NO_READINGS_24H
    }

    /** Which C-S01-06 banner text applies (CP-12 §7.5; key chosen by CP-02 {@code SurfaceKeys}). */
    public enum BannerKind {
        /** „Alarms are off”. */
        ALARMS_OFF,
        /** „Low alarm is off”. */
        LOW_ALARM_OFF,
        /** „High alarm is off”. */
        HIGH_ALARM_OFF,
        /** „Low and high alarms are off”. */
        LOW_HIGH_ALARMS_OFF,
        /** „Alarms may not ring — some phone settings are missing”. */
        PERMISSION_MISSING,
        /** „Nightscout is not responding”. */
        NS_NOT_RESPONDING,
        /** „Nightscout rejected the access token”. */
        TOKEN_REJECTED,
        /** „This token can't read entries and treatments …”. */
        TOKEN_PERMISSION_READ,
        /** „This token can't save, edit or delete entries …”. */
        TOKEN_PERMISSION_WRITE,
        /** „This address doesn't look like Nightscout.”. */
        NOT_NIGHTSCOUT,
        /** „Nightscout version is too old (15.0.9 or newer needed)”. */
        VERSION_TOO_OLD,
        /** „No new readings for {n} min — …”. */
        DATA_LOSS,
        /** „No internet connection — showing data from {n} min ago”. */
        OFFLINE
    }

    /** C-S01-06 banner action (A-S01-9). */
    public enum BannerAction {
        /** No action (offline). */
        NONE,
        /** „Turn on” → IF-25. */
        TURN_ON,
        /** „Fix” → IF-29 {@code openReadiness(CHECK)}. */
        FIX,
        /** „Retry” → IF-21 {@code retry()} ({@code requestTick(USER_RETRY)}). */
        RETRY,
        /** „Fix connection” → IF-29 {@code openConnection(EDIT)}. */
        FIX_CONNECTION
    }

    /** Banner container role (M3-08). */
    public enum BannerRole {
        /** Alarms off / single alarm off: {@code tertiaryContainer}. */
        TERTIARY,
        /** Readiness missing, connection errors, data loss: {@code errorContainer}. */
        ERROR,
        /** Offline: {@code secondaryContainer}. */
        SECONDARY
    }

    /**
     * One C-S01-06 banner.
     *
     * @param kind          text
     * @param minutes       {n} for {@link BannerKind#DATA_LOSS} / {@link BannerKind#OFFLINE}, else 0
     * @param action        action
     * @param role          container role
     * @param actionEnabled the action button is enabled (GIR-1: „Retry” disabled while fetching)
     */
    public record BannerUi(BannerKind kind, long minutes, BannerAction action, BannerRole role,
                           boolean actionEnabled) {

        /**
         * Validates the components.
         *
         * @throws NullPointerException if an enum is null
         */
        public BannerUi {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(role, "role");
        }
    }
}
