package app.lightscout.feature.onboarding.connection;

import androidx.annotation.StringRes;

import app.lightscout.feature.onboarding.R;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Helper;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Primary;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.connection.ConnectionInput.InputError;

/**
 * S-06 copy mapping to the design §17 keys (CP-14 §7.7; CP-00A R-51). Pure mapping, JVM-tested.
 */
public final class ConnectionCopy {

    private ConnectionCopy() {
    }

    /**
     * Title of C-S06-01.
     *
     * @param mode screen mode
     * @return {@code s06_title_onboarding} or {@code s06_title_edit}
     */
    @StringRes
    public static int title(ConnectionMode mode) {
        return mode == ConnectionMode.EDIT ? R.string.s06_title_edit : R.string.s06_title_onboarding;
    }

    /**
     * Label of a C-S06-05 row; the VERSION label takes the server version as {@code %1$s}.
     *
     * @param row the row
     * @return the string resource
     */
    @StringRes
    public static int rowLabel(CheckRow row) {
        switch (row) {
            case REACHABLE:
                return R.string.s06_check_reachable;
            case VERSION:
                return R.string.s06_check_version;
            case READ:
                return R.string.s06_check_read;
            case WRITE:
            default:
                return R.string.s06_check_write;
        }
    }

    /**
     * Message of a failing row (R-51); {@code s06_error_version} takes the server version as {@code %1$s}.
     *
     * @param failure the failure
     * @return the string resource
     */
    @StringRes
    public static int failureMessage(CheckFailure failure) {
        switch (failure) {
            case NOT_NIGHTSCOUT:
                return R.string.s06_error_not_nightscout;
            case VERSION_TOO_OLD:
                return R.string.s06_error_version;
            case TOKEN_REJECTED:
                return R.string.s06_error_token_rejected;
            case NO_READ:
                return R.string.s06_error_no_read;
            case NO_WRITE:
                return R.string.s06_error_no_write;
            case UNREACHABLE:
            default:
                return R.string.s06_error_unreachable;
        }
    }

    /**
     * Field error for C-12 checks 1–2.
     *
     * @param error the input error
     * @return the string resource
     */
    @StringRes
    public static int inputError(InputError error) {
        switch (error) {
            case URL_REQUIRED:
                return R.string.s06_error_url_required;
            case URL_HTTPS:
                return R.string.s06_error_url_https;
            case URL_INVALID:
                return R.string.s06_error_url_invalid;
            case TOKEN_REQUIRED:
            default:
                return R.string.s06_error_token_required;
        }
    }

    /**
     * Label of C-S06-07.
     *
     * @param primary the primary action
     * @return the string resource
     */
    @StringRes
    public static int primary(Primary primary) {
        switch (primary) {
            case CONTINUE:
                return R.string.s06_continue;
            case SAVE:
                return R.string.s06_save;
            case CONNECT:
            default:
                return R.string.s06_connect;
        }
    }

    /**
     * Helper text of a state, or 0 for none.
     *
     * @param helper the helper
     * @return the string resource, or 0
     */
    @StringRes
    public static int helper(Helper helper) {
        switch (helper) {
            case EMPTY_FIELDS:
                return R.string.s06_helper_empty;
            case OFFLINE:
                return R.string.s06_offline;
            case NONE:
            default:
                return 0;
        }
    }
}
