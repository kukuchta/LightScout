package app.lightscout.usecases.status;

import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.dataloss.DataLossKind;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.StatusSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome → surface mapping as data (05 §9.1; CP-02 §8.3; CP-00A R-76, R-81). Returns design §17 string
 * <em>keys</em> (codes), never UI text; CP-12 / CP-09 / CP-07 render them. The three D-024 cause lines are the
 * only No-data cause keys.
 */
public final class SurfaceKeys {

    /** S-01 data-loss banner (OK, no new reading ≥ threshold). */
    public static final String S01_ERROR_DATA_LOSS = "s01_error_data_loss";
    /** S-01 offline banner. */
    public static final String S01_OFFLINE = "s01_offline";
    /** S-01 Nightscout-not-responding banner. */
    public static final String S01_ERROR_NS_NOT_RESPONDING = "s01_error_ns_not_responding";
    /** S-01 token-rejected banner. */
    public static final String S01_ERROR_TOKEN_REJECTED = "s01_error_token_rejected";
    /** S-01 server-too-old banner. */
    public static final String S01_ERROR_VERSION_TOO_OLD = "s01_error_version_too_old";
    /** S-01 readiness banner. */
    public static final String S01_PERMISSION_MISSING = "s01_permission_missing";
    /** S-01 „Alarms” off banner. */
    public static final String S01_ALARMS_OFF = "s01_alarms_off";
    /** S-01 Low off banner. */
    public static final String S01_LOW_ALARM_OFF = "s01_low_alarm_off";
    /** S-01 High off banner. */
    public static final String S01_HIGH_ALARM_OFF = "s01_high_alarm_off";
    /** S-01 Low and High off banner. */
    public static final String S01_LOW_HIGH_ALARMS_OFF = "s01_low_high_alarms_off";
    /** S-01 token-can't-read banner. */
    public static final String S01_ERROR_TOKEN_PERMISSION_READ = "s01_error_token_permission_read";
    /** S-01 token-can't-write banner. */
    public static final String S01_ERROR_TOKEN_PERMISSION_WRITE = "s01_error_token_permission_write";
    /** S-01 not-Nightscout banner. */
    public static final String S01_ERROR_NOT_NIGHTSCOUT = "s01_error_not_nightscout";
    /** Banner action „Retry”. */
    public static final String ACTION_RETRY = "s01_banner_action_retry";
    /** Banner action „Fix connection”. */
    public static final String ACTION_FIX_CONNECTION = "s01_banner_action_fix_connection";
    /** Banner action „Fix” (→ S-07). */
    public static final String ACTION_FIX = "s01_banner_action_fix";
    /** Banner action „Turn on”. */
    public static final String ACTION_TURN_ON = "s01_banner_action_turn_on";
    /** S-10 normal text. */
    public static final String S10_TEXT = "s10_text";
    /** S-10 data-loss text. */
    public static final String S10_TEXT_DATA_LOSS = "s10_text_data_loss";
    /** S-10 first-use text. */
    public static final String S10_TEXT_FIRST_USE = "s10_text_first_use";
    /** S-10 offline text. */
    public static final String S10_OFFLINE = "s10_offline";
    /** S-10 Nightscout-not-responding text. */
    public static final String S10_ERROR_NS_NOT_RESPONDING = "s10_error_ns_not_responding";
    /** S-10 token-rejected text. */
    public static final String S10_ERROR_TOKEN_REJECTED = "s10_error_token_rejected";
    /** S-10 „ · Alarms off” suffix. */
    public static final String S10_ALARMS_OFF_SUFFIX = "s10_text_alarms_off_suffix";
    /** Cause line: uploader. */
    public static final String CAUSE_UPLOADER = "s09_cause_uploader";
    /** Cause line: no internet. */
    public static final String CAUSE_NO_INTERNET = "s09_cause_no_internet";
    /** Cause line: Nightscout not responding. */
    public static final String CAUSE_NS_NOT_RESPONDING = "s09_cause_ns_not_responding";

    private SurfaceKeys() {
    }

    /**
     * Surface codes for one snapshot.
     *
     * @param s01Banner  S-01 banner key; {@code null} = no banner
     * @param s01Action  S-01 banner action key; nullable
     * @param s10Text    S-10 text key
     * @param s10Suffix  S-10 suffix key; nullable
     * @param causeLine  No-data cause line (S-09 / S-11); {@code null} unless data loss
     */
    public record SurfaceCodes(String s01Banner, String s01Action, String s10Text, String s10Suffix,
                               String causeLine) {
    }

    /**
     * Maps a snapshot to its surface codes.
     *
     * @param s the snapshot
     * @return the codes
     */
    public static SurfaceCodes of(StatusSnapshot s) {
        String banner = null;
        String action = null;
        switch (s.bannerTop()) {
            case ALARMS_OFF -> {
                banner = S01_ALARMS_OFF;
                action = ACTION_TURN_ON;
            }
            case SINGLE_ALARM_OFF -> {
                banner = singleOffBanner(s);
                action = ACTION_TURN_ON;
            }
            case READINESS -> {
                banner = S01_PERMISSION_MISSING;
                action = ACTION_FIX;
            }
            case CONNECTION_OR_DATA_LOSS -> {
                String[] pair = connectionBanner(s);
                banner = pair[0];
                action = pair[1];
            }
            case OFFLINE -> banner = S01_OFFLINE;
            case NONE -> {
                // no banner
            }
        }
        String cause = s.dataLoss().kind() == DataLossKind.DATA_LOSS ? causeLine(s.dataLoss().cause()) : null;
        return new SurfaceCodes(banner, action, s10Text(s), s.alarmsEnabled() ? null : S10_ALARMS_OFF_SUFFIX,
                cause);
    }

    /**
     * One active S-01 fault chip (DCR-21, D-128).
     *
     * @param banner S-01 banner key
     * @param action banner action key; nullable
     */
    public record Fault(String banner, String action) {
    }

    /**
     * All S-01 fault chips active at once, in display order: alarms off / single alarm off, readiness,
     * connection / data loss, offline. Unlike {@link #of}, nothing is hidden by priority.
     *
     * @param s the snapshot
     * @return the faults; the first one equals {@code of(s).s01Banner()}
     */
    public static List<Fault> faults(StatusSnapshot s) {
        List<Fault> out = new ArrayList<>();
        if (!s.alarmsEnabled()) {
            out.add(new Fault(S01_ALARMS_OFF, ACTION_TURN_ON));
        } else if (!s.lowEnabled() || !s.highEnabled()) {
            out.add(new Fault(singleOffBanner(s), ACTION_TURN_ON));
        }
        if (s.readinessMissingRequired() > 0) {
            out.add(new Fault(S01_PERMISSION_MISSING, ACTION_FIX));
        }
        if (StatusProjector.connectionFault(s.lastOutcome(), s.connectionProblem(), s.dataLoss(), s.online(),
                s.latest())) {
            String[] pair = connectionBanner(s);
            out.add(new Fault(pair[0], pair[1]));
        }
        if (StatusProjector.offlineFault(s.lastOutcome(), s.online(), s.latest())) {
            out.add(new Fault(S01_OFFLINE, null));
        }
        return out;
    }

    private static String singleOffBanner(StatusSnapshot s) {
        return !s.lowEnabled() && !s.highEnabled() ? S01_LOW_HIGH_ALARMS_OFF
                : !s.lowEnabled() ? S01_LOW_ALARM_OFF : S01_HIGH_ALARM_OFF;
    }

    /**
     * Cause line key for a data-loss cause (DS:D-024).
     *
     * @param cause the cause
     * @return the key
     */
    public static String causeLine(DataLossCause cause) {
        return switch (cause) {
            case UPLOADER -> CAUSE_UPLOADER;
            case NO_INTERNET -> CAUSE_NO_INTERNET;
            case NS_NOT_RESPONDING -> CAUSE_NS_NOT_RESPONDING;
        };
    }

    /** R-76 banner text for CONNECTION_OR_DATA_LOSS, first match. */
    private static String[] connectionBanner(StatusSnapshot s) {
        if (either(s, FetchOutcome.SERVER_TOO_OLD)) {
            return new String[] {S01_ERROR_VERSION_TOO_OLD, ACTION_FIX_CONNECTION};
        }
        if (either(s, FetchOutcome.TOKEN_REJECTED)) {
            return new String[] {S01_ERROR_TOKEN_REJECTED, ACTION_FIX_CONNECTION};
        }
        if (either(s, FetchOutcome.PERMISSION_MISSING)) {
            return new String[] {S01_ERROR_TOKEN_PERMISSION_READ, ACTION_FIX_CONNECTION};
        }
        if (either(s, FetchOutcome.PERMISSION_WRITE_MISSING)) {
            return new String[] {S01_ERROR_TOKEN_PERMISSION_WRITE, ACTION_FIX_CONNECTION};
        }
        if (either(s, FetchOutcome.NOT_NIGHTSCOUT)) {
            return new String[] {S01_ERROR_NOT_NIGHTSCOUT, ACTION_FIX_CONNECTION};
        }
        if (either(s, FetchOutcome.NS_NOT_RESPONDING)) {
            return new String[] {S01_ERROR_NS_NOT_RESPONDING, ACTION_RETRY};
        }
        return new String[] {S01_ERROR_DATA_LOSS, ACTION_RETRY};
    }

    /** R-81 S-10 text, first match. */
    private static String s10Text(StatusSnapshot s) {
        if ((!s.online() || s.lastOutcome() == FetchOutcome.NO_INTERNET) && s.latest() != null) {
            return S10_OFFLINE;
        }
        if (either(s, FetchOutcome.TOKEN_REJECTED) || either(s, FetchOutcome.PERMISSION_MISSING)) {
            return S10_ERROR_TOKEN_REJECTED;
        }
        if (either(s, FetchOutcome.NS_NOT_RESPONDING) || either(s, FetchOutcome.NOT_NIGHTSCOUT)
                || either(s, FetchOutcome.SERVER_TOO_OLD)) {
            return S10_ERROR_NS_NOT_RESPONDING;
        }
        if (s.dataLoss().kind() == DataLossKind.FIRST_USE) {
            return S10_TEXT_FIRST_USE;
        }
        if (s.dataLoss().kind() == DataLossKind.DATA_LOSS) {
            return S10_TEXT_DATA_LOSS;
        }
        return S10_TEXT;
    }

    private static boolean either(StatusSnapshot s, FetchOutcome o) {
        return s.lastOutcome() == o || s.connectionProblem() == o;
    }
}
