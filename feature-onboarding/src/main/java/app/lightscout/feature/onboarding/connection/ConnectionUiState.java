package app.lightscout.feature.onboarding.connection;

import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.connection.ConnectionInput.InputError;

import java.util.List;

/**
 * What S-06 shows (CP-14 §7.3). Holds no secret: the token characters are never part of the state (SR-02), so
 * this record may be logged or compared freely.
 *
 * @param mode             onboarding or edit (title, primary label, back guard)
 * @param url              URL field text
 * @param urlFieldVersion  incremented whenever the model rewrites the URL field (prefill, „https://” added)
 * @param urlError         URL error shown under C-S06-03; nullable
 * @param tokenMode        what the token field holds
 * @param tokenFieldVersion incremented whenever the model rewrites the token field (placeholder, reveal, cleared)
 * @param tokenVisible     the token is shown in clear (password toggle)
 * @param tokenToggleEnabled the password toggle is usable (not on the untouched placeholder in onboarding mode — IR-94)
 * @param tokenError       token error shown under C-S06-04; nullable
 * @param tokenReenter     helper „For security, enter the token again” instead of the default helper (M3-28)
 * @param fieldsEnabled    inputs accept edits (not while checking or saving)
 * @param checking         C-S06-06 indicator and „Checking…” visible
 * @param results          C-S06-05 rows up to and including the failing one (R-73); empty = hidden
 * @param primary          C-S06-07 label
 * @param primaryEnabled   C-S06-07 enabled
 * @param helper           helper under the button area (empty-fields / offline), or none
 * @param backGuard        back / Up must ask „Discard changes?” (edit mode with unsaved changes — 06 §11 F17)
 * @param discardDialog    C-S06-08 is open
 */
public record ConnectionUiState(
        ConnectionMode mode,
        String url,
        int urlFieldVersion,
        InputError urlError,
        TokenMode tokenMode,
        int tokenFieldVersion,
        boolean tokenVisible,
        boolean tokenToggleEnabled,
        InputError tokenError,
        boolean tokenReenter,
        boolean fieldsEnabled,
        boolean checking,
        List<CheckResult> results,
        Primary primary,
        boolean primaryEnabled,
        Helper helper,
        boolean backGuard,
        boolean discardDialog) {

    /** Content of the token field. */
    public enum TokenMode {
        /** Nothing typed. */
        EMPTY,
        /** The user typed a token (memory only). */
        TYPED,
        /** Edit mode / stored connection: masked placeholder {@code s06_token_masked}, nothing decrypted (R-73). */
        STORED_MASKED,
        /** The untouched stored token, decrypted on „Show token” (D-027); still counts as untouched. */
        STORED_REVEALED
    }

    /** C-S06-07 label (A-S06-1…3). */
    public enum Primary {
        /** „Connect”: runs the checks. */
        CONNECT,
        /** „Continue” (onboarding, all ✓): store, then S-07. */
        CONTINUE,
        /** „Save” (edit, all ✓): store, then pop to the caller with „Connection saved”. */
        SAVE
    }

    /** Helper text under the fields (S-06 states). */
    public enum Helper {
        /** No helper. */
        NONE,
        /** Empty — first use: „Enter the address and token to connect”. */
        EMPTY_FIELDS,
        /** Offline: „No internet connection — connect when you're online.” */
        OFFLINE
    }

    /**
     * Creates the state with an unmodifiable copy of the rows.
     */
    public ConnectionUiState {
        results = List.copyOf(results);
    }
}
