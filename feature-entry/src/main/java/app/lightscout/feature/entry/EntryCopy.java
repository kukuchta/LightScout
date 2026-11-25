package app.lightscout.feature.entry;

import androidx.annotation.StringRes;

import app.lightscout.domain.entry.SourceKind;
import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.model.EntryType;
import app.lightscout.feature.entry.EntryFormUiState.Direction;

/**
 * Display mapping of S-02 (CP-13 §7.5, §7.6, §7.12; CP-00A R-51, R-83, DCR-04): model values → string resources.
 * Pure lookups (no Context), so the mapping is JVM-tested.
 */
public final class EntryCopy {

    private EntryCopy() {
    }

    /**
     * C-S02-05 chip / value-field label.
     *
     * @param t a loggable type
     * @return the string resource
     */
    @StringRes
    public static int typeName(EntryType t) {
        switch (t) {
            case BG_CHECK:
                return R.string.s02_type_bg_check;
            case CARBS:
                return R.string.s02_type_carbs;
            case BOLUS:
                return R.string.s02_type_bolus;
            case SITE_CHANGE:
                return R.string.s02_type_site_change;
            case SENSOR_CHANGE:
                return R.string.s02_type_sensor_change;
            default:
                throw new IllegalArgumentException("not loggable: " + t);
        }
    }

    /**
     * C-S02-06 suffix (the {@code :ui-core} unit keys, Q-54).
     *
     * @param t BG check, Carbs or Bolus
     * @return the string resource; 0 for types without a value
     */
    @StringRes
    public static int suffix(EntryType t) {
        if (t == null) {
            return 0;
        }
        switch (t) {
            case BG_CHECK:
                return app.lightscout.ui.core.R.string.s02_suffix_mgdl;
            case CARBS:
                return app.lightscout.ui.core.R.string.s02_suffix_g;
            case BOLUS:
                return app.lightscout.ui.core.R.string.s02_suffix_u;
            default:
                return 0;
        }
    }

    /**
     * Helper under the value field: the allowed range.
     *
     * @param t BG check, Carbs or Bolus
     * @return the string resource; 0 for types without a value
     */
    @StringRes
    public static int range(EntryType t) {
        if (t == null) {
            return 0;
        }
        switch (t) {
            case BG_CHECK:
                return R.string.s02_range_bg;
            case CARBS:
                return R.string.s02_range_carbs;
            case BOLUS:
                return R.string.s02_range_bolus;
            default:
                return 0;
        }
    }

    /**
     * Spoken unit for {@code s02_value_a11y} („Carbs, grams”).
     *
     * @param t BG check, Carbs or Bolus
     * @return the string resource; 0 for types without a value
     */
    @StringRes
    public static int spokenUnit(EntryType t) {
        if (t == null) {
            return 0;
        }
        switch (t) {
            case BG_CHECK:
                return R.string.a11y_unit_mgdl;
            case CARBS:
                return R.string.a11y_unit_g;
            case BOLUS:
                return R.string.a11y_unit_u;
            default:
                return 0;
        }
    }

    /**
     * Value / time validation message (DS:A-18, DS:A-19).
     *
     * @param code an S-02 validation code
     * @return the string resource
     */
    @StringRes
    public static int validation(ValidationCode code) {
        switch (code) {
            case TYPE_REQUIRED:
                return R.string.s02_helper_choose_type;
            case BG_RANGE:
                return R.string.s02_error_bg_range;
            case CARBS_RANGE:
                return R.string.s02_error_carbs_range;
            case BOLUS_RANGE:
                return R.string.s02_error_bolus_range;
            case TIME_FUTURE:
                return R.string.s02_error_time_future;
            case TIME_TOO_OLD:
                return R.string.s02_error_time_too_old;
            default:
                throw new IllegalArgumentException("not an S-02 code: " + code);
        }
    }

    /**
     * C-S02-11 text (R-51).
     *
     * @param m the message
     * @return the string resource; 0 = hidden
     */
    @StringRes
    public static int message(EntryFormModel.Message m) {
        switch (m) {
            case OFFLINE_CREATE:
                return R.string.s02_offline_create;
            case OFFLINE_EDIT:
                return R.string.s02_offline_edit;
            case NS_NOT_RESPONDING:
                return R.string.s02_error_ns_not_responding;
            case TOKEN_REJECTED:
                return R.string.s02_error_token_rejected;
            case REJECTED:
                return R.string.s02_error_rejected;
            case CONFLICT:
                return R.string.s02_error_conflict;
            case UNSURE_SAVED:
                return R.string.s02_error_unsure_saved;
            case NONE:
            default:
                return 0;
        }
    }

    /**
     * Whether C-S02-11 shows the offline text (info) rather than an error.
     *
     * @param m the message
     * @return {@code true} for the offline variants
     */
    public static boolean isOffline(EntryFormModel.Message m) {
        return m == EntryFormModel.Message.OFFLINE_CREATE || m == EntryFormModel.Message.OFFLINE_EDIT;
    }

    /**
     * C-S02-10 template (R-83, DCR-04.5).
     *
     * @param d direction
     * @return the string resource
     */
    @StringRes
    public static int similar(Direction d) {
        switch (d) {
            case EARLIER:
                return R.string.s02_similar_entry_earlier;
            case LATER:
                return R.string.s02_similar_entry_later;
            case SAME_TIME:
            default:
                return R.string.s02_similar_entry_same_time;
        }
    }

    /**
     * M-6 source label resource; {@link SourceKind#UPLOADER} uses the uploader name instead.
     *
     * @param kind the source kind
     * @return the string resource; 0 for an uploader
     */
    @StringRes
    public static int source(SourceKind kind) {
        switch (kind) {
            case LIGHTSCOUT:
                return R.string.s01_tooltip_source_lightscout;
            case NIGHTSCOUT:
                return R.string.s01_tooltip_source_nightscout;
            case UPLOADER:
            default:
                return 0;
        }
    }
}
