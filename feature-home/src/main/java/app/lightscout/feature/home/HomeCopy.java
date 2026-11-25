package app.lightscout.feature.home;

import app.lightscout.feature.home.HomeUiState.BannerAction;
import app.lightscout.feature.home.HomeUiState.BannerKind;
import app.lightscout.feature.home.HomeUiState.EmptyMessage;
import app.lightscout.feature.home.HomeUiState.RangeLabel;

/**
 * S-01 copy and icon table (CP-12 §7.5, §7.6, §7.9; CP-00A R-76): maps display enums to string and drawable
 * resources. Resource ids only, so it is JVM-tested together with the verbatim strings.
 */
public final class HomeCopy {

    private HomeCopy() {
    }

    public static int snoozeType(app.lightscout.domain.alarm.AlarmType type) {
        return switch (type) {
            case URGENT_LOW -> R.string.s01_snooze_type_urgent_low;
            case LOW -> R.string.s01_snooze_type_low;
            case HIGH -> R.string.s01_snooze_type_high;
            case NO_DATA -> R.string.s01_snooze_type_no_data;
            case NOT_RUNNING -> R.string.s01_snooze_type_not_running;
        };
    }

    public static int chipText(BannerKind kind) {
        return switch (kind) {
            case ALARMS_OFF -> R.string.s01_chip_alarms_off;
            case LOW_ALARM_OFF -> R.string.s01_chip_low_off;
            case HIGH_ALARM_OFF -> R.string.s01_chip_high_off;
            case LOW_HIGH_ALARMS_OFF -> R.string.s01_chip_low_high_off;
            case PERMISSION_MISSING -> R.string.s01_chip_readiness;
            case VERSION_TOO_OLD -> R.string.s01_chip_version;
            case TOKEN_REJECTED -> R.string.s01_chip_token;
            case TOKEN_PERMISSION_READ, TOKEN_PERMISSION_WRITE -> R.string.s01_chip_token_permissions;
            case NOT_NIGHTSCOUT -> R.string.s01_chip_not_nightscout;
            case NS_NOT_RESPONDING -> R.string.s01_chip_server;
            case DATA_LOSS -> R.string.s01_chip_uploader;
            case OFFLINE -> R.string.s01_chip_offline;
        };
    }

    /**
     * Banner text.
     *
     * @param kind the banner
     * @return string resource ({@link BannerKind#DATA_LOSS} / {@link BannerKind#OFFLINE} take {n} as {@code %1$d})
     */
    public static int bannerText(BannerKind kind) {
        switch (kind) {
            case ALARMS_OFF:
                return R.string.s01_alarms_off;
            case LOW_ALARM_OFF:
                return R.string.s01_low_alarm_off;
            case HIGH_ALARM_OFF:
                return R.string.s01_high_alarm_off;
            case LOW_HIGH_ALARMS_OFF:
                return R.string.s01_low_high_alarms_off;
            case PERMISSION_MISSING:
                return R.string.s01_permission_missing;
            case NS_NOT_RESPONDING:
                return R.string.s01_error_ns_not_responding;
            case TOKEN_REJECTED:
                return R.string.s01_error_token_rejected;
            case TOKEN_PERMISSION_READ:
                return R.string.s01_error_token_permission_read;
            case TOKEN_PERMISSION_WRITE:
                return R.string.s01_error_token_permission_write;
            case NOT_NIGHTSCOUT:
                return R.string.s01_error_not_nightscout;
            case VERSION_TOO_OLD:
                return R.string.s01_error_version_too_old;
            case DATA_LOSS:
                return R.string.s01_error_data_loss;
            case OFFLINE:
            default:
                return R.string.s01_offline;
        }
    }

    /**
     * Banner text takes the {n} minutes argument.
     *
     * @param kind the banner
     * @return {@code true} for data loss and offline
     */
    public static boolean bannerTakesMinutes(BannerKind kind) {
        return kind == BannerKind.DATA_LOSS || kind == BannerKind.OFFLINE;
    }

    /**
     * Banner action label.
     *
     * @param action the action
     * @return string resource, or 0 for {@link BannerAction#NONE} (no button)
     */
    public static int bannerAction(BannerAction action) {
        switch (action) {
            case TURN_ON:
                return R.string.s01_banner_action_turn_on;
            case FIX:
                return R.string.s01_banner_action_fix;
            case RETRY:
                return R.string.s01_banner_action_retry;
            case FIX_CONNECTION:
                return R.string.s01_banner_action_fix_connection;
            case NONE:
            default:
                return 0;
        }
    }

    /**
     * Banner leading icon (CP-00A R-76; decorative, the text carries the meaning).
     *
     * @param kind the banner
     * @return drawable resource
     */
    public static int bannerIcon(BannerKind kind) {
        switch (kind) {
            case ALARMS_OFF:
            case LOW_ALARM_OFF:
            case HIGH_ALARM_OFF:
            case LOW_HIGH_ALARMS_OFF:
                return R.drawable.ic_s01_banner_alarms_off;
            case PERMISSION_MISSING:
                return R.drawable.ic_s01_banner_warning;
            case OFFLINE:
                return R.drawable.ic_s01_banner_offline;
            default:
                return R.drawable.ic_s01_banner_error;
        }
    }

    /**
     * Range label text.
     *
     * @param label the label
     * @return string resource
     */
    public static int rangeLabel(RangeLabel label) {
        switch (label) {
            case URGENT_LOW:
                return R.string.s01_range_urgent_low;
            case LOW:
                return R.string.s01_range_low;
            case HIGH:
                return R.string.s01_range_high;
            case NONE:
                return R.string.s01_range_in_range;
            default:
                throw new IllegalArgumentException("Unknown range label: " + label);
        }
    }

    /**
     * Empty-state message in the chart area.
     *
     * @param message the message
     * @return string resource, or 0 for {@link EmptyMessage#NONE}
     */
    public static int emptyMessage(EmptyMessage message) {
        switch (message) {
            case FIRST_USE:
                return R.string.s01_state_first_use;
            case NO_READINGS_24H:
                return R.string.s01_state_no_readings_24h;
            case NONE:
            default:
                return 0;
        }
    }
}
