package app.lightscout.feature.onboarding.connection;

import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.feature.onboarding.StateStore;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Helper;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Primary;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.TokenMode;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.StoredConnection;
import app.lightscout.usecases.connection.ConnectionInput;
import app.lightscout.usecases.connection.ConnectionInput.InputError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * S-06 screen logic (CP-14 §7.3 incl. DS:CR-08 / DS:D-083; CP-00A R-28, R-51, R-71, R-73), free of Android types
 * so that it is JVM-tested. Main thread only; IF-24 callbacks arrive on {@code ioExecutor} and are moved to the
 * main executor here.
 *
 * <p>Rules implemented:
 * <ul>
 *   <li>C-12 check order: checks 1–2 (URL, token input) use the pure {@link ConnectionInput} validator <b>before</b>
 *       IF-24 {@code verify} is called (Q-44); nothing is sent while an input error is shown. Checks 3–6 run inside
 *       {@code verify}, which never sends the token before IF-31 confirmed Nightscout ≥ 15.0.0 with API v3.</li>
 *   <li>Result mapping (R-51): rows are shown up to the failing one; a failed {@code verify} result
 *       ({@code STORAGE_ERROR}) is shown as „Nightscout reachable” ✕ with {@code s06_error_unreachable} (CR-05).
 *       A refused {@code save} ({@code NOT_NIGHTSCOUT}, Q-45) shows {@code s06_error_not_nightscout}.</li>
 *   <li>Token hygiene (SR-02, SR-28): the token is kept only in this onboarding-scoped object as a {@code char[]},
 *       copied for each IF-24 call and wiped after the callback, wiped in {@link #clear()}; it is never written to
 *       the {@link StateStore} (only the URL and a token <i>state</i> flag are).</li>
 *   <li>Process death (DS:A-24, M3-28, R-73): the URL is restored; a typed token is gone and the helper
 *       „For security, enter the token again” is shown; an untouched stored-token placeholder is re-created.</li>
 *   <li>Edit mode (R-73): URL from {@code loadStored}, token as masked placeholder without decrypting; the first
 *       keystroke replaces the placeholder; „Show token” on the untouched field calls {@code revealStoredToken};
 *       untouched → {@code verify/save(url, null)}. Unsaved changes enable the back guard (06 §11 F17).</li>
 *   <li>GIR-1: „Connect” / „Continue” / „Save” are ignored while a check or save is in flight.</li>
 * </ul>
 */
public final class ConnectionModel {

    /** One-time requests to the fragment. */
    public enum Event {
        /** Onboarding: the connection is stored — open S-07 (mode onboarding). */
        OPEN_READINESS,
        /** Edit: the connection is stored — pop to the caller with {@code CONNECTION_SAVED} (R-38). */
        FINISH_SAVED,
        /** Leave the screen (Up / back without unsaved changes, or after „Discard”). */
        LEAVE
    }

    /** Receives the state and one-time events on the main thread. */
    public interface Listener {

        /**
         * The visible state changed.
         *
         * @param state the new state
         */
        void onState(ConnectionUiState state);

        /**
         * A one-time request.
         *
         * @param event the request
         */
        void onEvent(Event event);
    }

    static final String KEY_URL = "s06_url";
    static final String KEY_TOKEN_STATE = "s06_token_state";
    static final String TOKEN_STATE_NONE = "NONE";
    static final String TOKEN_STATE_TYPED = "TYPED";
    static final String TOKEN_STATE_STORED = "STORED";
    private static final String HTTPS_PREFIX = "https://";
    private static final String SCHEME_SEPARATOR = "://";
    private static final int ROW_COUNT = CheckRow.values().length;

    private enum Phase { IDLE, CHECKING, SAVING }

    private final ConnectionMode mode;
    private final ConnectionService service;
    private final StateStore store;
    private final Executor main;
    private Listener listener;

    private String url;
    private boolean urlTouched;
    private InputError urlError;
    private int urlFieldVersion;

    private char[] token;
    private TokenMode tokenMode = TokenMode.EMPTY;
    private InputError tokenError;
    private boolean tokenReenter;
    private boolean tokenVisible;
    private int tokenFieldVersion;
    private boolean revealing;

    private boolean started;
    private boolean storedLoaded;
    private String storedUrl;
    private boolean storedHasToken;
    private boolean online;

    private Phase phase = Phase.IDLE;
    private List<CheckResult> results = List.of();
    private boolean verified;
    private boolean connectionSaved;
    private boolean leaving;
    private boolean discardDialog;
    private int generation;

    /**
     * Creates the model, restoring the URL and the token state flag from the saved state (process death).
     *
     * @param mode    onboarding or edit (S-06 argument)
     * @param service IF-24
     * @param store   saved state (URL and token state flag only)
     * @param main    main-thread executor
     * @param online  initial IF-21 {@code StatusSnapshot.online} (valid before onboarding completes — R-37)
     */
    public ConnectionModel(ConnectionMode mode, ConnectionService service, StateStore store, Executor main,
            boolean online) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.service = Objects.requireNonNull(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.main = Objects.requireNonNull(main, "main");
        this.online = online;
        String restoredUrl = store.getString(KEY_URL);
        this.url = restoredUrl == null ? "" : restoredUrl;
        String tokenState = store.getString(KEY_TOKEN_STATE);
        if (TOKEN_STATE_TYPED.equals(tokenState)) {
            tokenReenter = true;                       // a typed token never survives process death (DS:A-24)
        } else if (TOKEN_STATE_STORED.equals(tokenState)) {
            tokenMode = TokenMode.STORED_MASKED;       // untouched placeholder re-created (R-73)
        }
        urlTouched = !url.isEmpty();
    }

    /**
     * Sets the listener and delivers the current state.
     *
     * @param l the listener, or {@code null} to detach
     */
    public void setListener(Listener l) {
        listener = l;
        publish();
    }

    /**
     * Loads the stored connection once (prefill: URL + masked token; edit-mode baseline for unsaved changes).
     * Fields the user already filled (or restored ones) are never overwritten.
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        service.loadStored(r -> main.execute(() -> onStoredLoaded(r)));
    }

    private void onStoredLoaded(Result<StoredConnection> r) {
        storedLoaded = true;
        StoredConnection stored = r.ok() ? r.value() : null;   // STORAGE_ERROR → treated as nothing stored
        if (stored != null) {
            storedUrl = stored.baseUrl();
            storedHasToken = stored.hasToken();
            if (url.isEmpty() && tokenMode == TokenMode.EMPTY && !tokenReenter) {
                url = storedUrl;
                urlTouched = true;
                urlFieldVersion++;
                if (storedHasToken) {
                    tokenMode = TokenMode.STORED_MASKED;
                    tokenFieldVersion++;
                }
                persist();
            }
        }
        if (tokenMode == TokenMode.STORED_MASKED && stored != null && !storedHasToken) {
            tokenMode = TokenMode.EMPTY;               // defensive: no stored token behind the placeholder
            tokenFieldVersion++;
            persist();
        }
        publish();
    }

    /**
     * Returns the current state.
     *
     * @return the state (no secret inside)
     */
    public ConnectionUiState state() {
        boolean idle = phase == Phase.IDLE;
        Primary primary = !verified ? Primary.CONNECT
                : mode == ConnectionMode.ONBOARDING ? Primary.CONTINUE : Primary.SAVE;
        // Q-77: offline disables C-S06-07 whatever its label, with the offline helper whenever offline
        boolean primaryEnabled = idle && online && (verified || inputsFilled());
        Helper helper = Helper.NONE;
        if (!online) {
            helper = Helper.OFFLINE;
        } else if (idle && !verified && !inputsFilled()) {
            helper = Helper.EMPTY_FIELDS;
        }
        return new ConnectionUiState(mode, url, urlFieldVersion, urlError, tokenMode, tokenFieldVersion,
                tokenVisible, tokenToggleEnabled(), tokenError, tokenReenter, idle, phase == Phase.CHECKING, results, primary,
                primaryEnabled, helper, hasUnsavedChanges(), discardDialog);
    }

    /**
     * Returns a copy of the token characters for the field (typed or revealed), or {@code null}. The caller wipes
     * the copy after use (SR-02).
     *
     * @return a fresh copy, or {@code null}
     */
    public char[] tokenForField() {
        if ((tokenMode == TokenMode.TYPED || tokenMode == TokenMode.STORED_REVEALED) && token != null) {
            return token.clone();
        }
        return null;
    }

    /**
     * The user edited the URL field (A-S06-4: an earlier check result is cleared).
     *
     * @param text the field text
     */
    public void onUrlEdited(String text) {
        String t = text == null ? "" : text;
        if (t.equals(url)) {
            return;
        }
        url = t;
        urlTouched = true;
        urlError = null;
        invalidateCheck();
        persist();
        publish();
    }

    /** The URL field lost focus: validate (on blur) and add „https://” when no scheme was typed. */
    public void onUrlFocusLost() {
        if (!urlTouched) {
            return;
        }
        urlError = validateUrl();
        publish();
    }

    /**
     * The user edited the token field (not the placeholder). Takes ownership of {@code chars} (wiped later).
     *
     * @param chars the full field text as a fresh array
     */
    public void onTokenTyped(char[] chars) {
        replaceToken(chars);
        publish();
    }

    /**
     * First keystroke on the untouched placeholder: it replaces the placeholder (R-73). Takes ownership of
     * {@code inserted}; the field is rewritten to exactly these characters.
     *
     * @param inserted the characters typed or pasted (empty for a deletion)
     */
    public void onPlaceholderKeystroke(char[] inserted) {
        replaceToken(inserted);
        tokenFieldVersion++;
        publish();
    }

    private void replaceToken(char[] chars) {
        wipe(token);
        char[] c = chars == null ? new char[0] : chars;
        token = c.length == 0 ? null : c;
        tokenMode = c.length == 0 ? TokenMode.EMPTY : TokenMode.TYPED;
        tokenError = null;
        if (c.length > 0) {
            tokenReenter = false;
        }
        invalidateCheck();
        persist();
    }

    /**
     * The password toggle was tapped: on the untouched placeholder it decrypts the stored token on demand
     * (D-027, R-73); otherwise it switches between masked and clear text.
     */
    public void onTokenToggle() {
        if (!tokenToggleEnabled()) {
            return;   // IR-94: revealStoredToken only in edit mode
        }
        if (tokenMode == TokenMode.STORED_MASKED) {
            if (revealing || phase != Phase.IDLE) {
                return;
            }
            revealing = true;
            service.revealStoredToken(r -> main.execute(() -> onRevealed(r)));
            return;
        }
        tokenVisible = !tokenVisible;
        publish();
    }

    private void onRevealed(Result<char[]> r) {
        revealing = false;
        char[] value = r.ok() ? r.value() : null;
        if (tokenMode != TokenMode.STORED_MASKED || value == null || value.length == 0) {
            wipe(value);   // no copy exists for a failed reveal: the placeholder stays
            publish();
            return;
        }
        wipe(token);
        token = value;
        tokenMode = TokenMode.STORED_REVEALED;
        tokenVisible = true;
        tokenFieldVersion++;
        publish();
    }

    /**
     * IF-21 connectivity changed (S-06 Offline state).
     *
     * @param isOnline {@code StatusSnapshot.online}
     */
    public void setOnline(boolean isOnline) {
        if (online == isOnline) {
            return;
        }
        online = isOnline;
        publish();
    }

    /** C-S06-07 tapped: „Connect” runs the checks; „Continue” / „Save” store the verified connection. */
    public void onPrimaryAction() {
        if (phase != Phase.IDLE || !online) {
            return;   // GIR-1; Q-77 offline
        }
        if (verified) {
            store();
        } else {
            connect();
        }
    }

    private void connect() {
        if (!online) {
            return;
        }
        urlTouched = true;
        urlError = validateUrl();
        tokenError = validateToken();
        if (urlError != null || tokenError != null) {
            publish();   // C-12 checks 1–2 failed: nothing is sent (Q-44)
            return;
        }
        phase = Phase.CHECKING;
        results = List.of();
        final int g = ++generation;
        final char[] arg = tokenArgument();
        final String u = url;
        publish();
        service.verify(u, arg, r -> {
            wipe(arg);
            main.execute(() -> onVerified(g, r));
        });
    }

    private void onVerified(int g, Result<List<CheckResult>> r) {
        if (g != generation) {
            return;
        }
        phase = Phase.IDLE;
        if (!r.ok()) {
            results = List.of(failedRow(CheckRow.REACHABLE, CheckFailure.UNREACHABLE));   // CR-05 / R-51
            verified = false;
        } else {
            List<CheckResult> rows = r.value() == null ? List.of() : r.value();
            results = rows;
            verified = rows.size() == ROW_COUNT && rows.stream().allMatch(CheckResult::passed);
        }
        publish();
    }

    private void store() {
        if (mode == ConnectionMode.ONBOARDING && connectionSaved) {
            emit(Event.OPEN_READINESS);   // already stored in this flow (back from S-07 — R-38)
            return;
        }
        phase = Phase.SAVING;
        final int g = ++generation;
        final char[] arg = tokenArgument();
        final String u = url;
        publish();
        service.save(u, arg, r -> {
            wipe(arg);
            main.execute(() -> onSaved(g, r));
        });
    }

    private void onSaved(int g, Result<?> r) {
        if (g != generation) {
            return;
        }
        phase = Phase.IDLE;
        if (r.ok()) {
            if (mode == ConnectionMode.ONBOARDING) {
                connectionSaved = true;
                persist();
                publish();
                emit(Event.OPEN_READINESS);
            } else {
                leaving = true;
                publish();
                emit(Event.FINISH_SAVED);
            }
            return;
        }
        verified = false;
        results = saveFailureRows(r.outcome());
        publish();
    }

    /** Rows for a refused / failed {@code save}: the failing row after the rows that passed (R-51, R-73). */
    private List<CheckResult> saveFailureRows(FetchOutcome outcome) {
        if (outcome == FetchOutcome.NOT_NIGHTSCOUT) {
            return List.of(failedRow(CheckRow.REACHABLE, CheckFailure.NOT_NIGHTSCOUT));   // Q-45
        }
        if (outcome == FetchOutcome.TOKEN_REJECTED) {
            String version = null;
            for (CheckResult row : results) {
                if (row.row() == CheckRow.VERSION) {
                    version = row.serverVersion();
                }
            }
            List<CheckResult> rows = new ArrayList<>();
            rows.add(new CheckResult(CheckRow.REACHABLE, true, null, null));
            rows.add(new CheckResult(CheckRow.VERSION, true, null, version));
            rows.add(failedRow(CheckRow.READ, CheckFailure.TOKEN_REJECTED));
            return rows;
        }
        return List.of(failedRow(CheckRow.REACHABLE, CheckFailure.UNREACHABLE));   // STORAGE_ERROR → CR-05
    }

    /** Up / system back. Edit mode with unsaved changes opens C-S06-08; otherwise the screen is left. */
    public void onBackRequested() {
        if (hasUnsavedChanges()) {
            discardDialog = true;
            publish();
        } else {
            emit(Event.LEAVE);
        }
    }

    /** C-S06-08 „Discard”. */
    public void onDiscardConfirmed() {
        discardDialog = false;
        leaving = true;
        publish();
        emit(Event.LEAVE);
    }

    /** C-S06-08 „Keep editing” (or the dialog was cancelled): input is kept. */
    public void onDiscardCancelled() {
        discardDialog = false;
        publish();
    }

    /** Wipes the token and drops pending callbacks (ViewModel cleared). */
    public void clear() {
        generation++;
        wipe(token);
        token = null;
        listener = null;
    }

    /** IR-94 (R-28, R-73): the stored token may be revealed only in edit mode; in onboarding the toggle is off on the placeholder. */
    private boolean tokenToggleEnabled() {
        return !(tokenMode == TokenMode.STORED_MASKED && mode == ConnectionMode.ONBOARDING);
    }

    private boolean hasUnsavedChanges() {
        if (mode != ConnectionMode.EDIT || leaving || !storedLoaded || phase == Phase.SAVING) {
            return false;
        }
        String base = storedUrl == null ? "" : storedUrl;
        boolean urlChanged = !url.trim().equals(base);
        boolean tokenUntouched = tokenMode == TokenMode.STORED_MASKED || tokenMode == TokenMode.STORED_REVEALED;
        boolean tokenChanged = tokenMode == TokenMode.TYPED || tokenUntouched != storedHasToken;
        return urlChanged || tokenChanged;
    }

    private boolean inputsFilled() {
        boolean tokenPresent = tokenMode != TokenMode.EMPTY;
        return !url.trim().isEmpty() && tokenPresent;
    }

    /** C-12 check 1 (SR-28, SR-14): adds „https://” to a valid scheme-less address. */
    private InputError validateUrl() {
        ConnectionInput.UrlCheck check = ConnectionInput.checkUrl(url);
        if (check.valid() && !url.contains(SCHEME_SEPARATOR)) {
            url = HTTPS_PREFIX + url.trim();
            urlFieldVersion++;
            persist();
        }
        return check.error();
    }

    /** C-12 check 2: required, trimmed, no spaces; the untouched stored token needs no input check. */
    private InputError validateToken() {
        switch (tokenMode) {
            case TYPED:
                return ConnectionInput.checkToken(token);
            case STORED_MASKED:
            case STORED_REVEALED:
                return null;
            case EMPTY:
            default:
                return InputError.TOKEN_REQUIRED;
        }
    }

    /** A trimmed copy of the typed token, or {@code null} for the untouched stored token (R-73). */
    private char[] tokenArgument() {
        return tokenMode == TokenMode.TYPED ? ConnectionInput.normaliseToken(token) : null;
    }

    private void invalidateCheck() {
        if (phase != Phase.IDLE) {
            generation++;   // a late result of the old input is dropped
            phase = Phase.IDLE;
        }
        results = List.of();
        verified = false;
        connectionSaved = false;
    }

    private void persist() {
        store.putString(KEY_URL, url);
        store.putString(KEY_TOKEN_STATE, tokenState());
    }

    private String tokenState() {
        if (connectionSaved) {
            return TOKEN_STATE_STORED;
        }
        switch (tokenMode) {
            case TYPED:
                return TOKEN_STATE_TYPED;
            case STORED_MASKED:
            case STORED_REVEALED:
                return TOKEN_STATE_STORED;
            case EMPTY:
            default:
                return tokenReenter ? TOKEN_STATE_TYPED : TOKEN_STATE_NONE;
        }
    }

    private void publish() {
        if (listener != null) {
            listener.onState(state());
        }
    }

    private void emit(Event e) {
        if (listener != null) {
            listener.onEvent(e);
        }
    }

    private static CheckResult failedRow(CheckRow row, CheckFailure failure) {
        return new CheckResult(row, false, failure, null);
    }

    private static void wipe(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }
}
