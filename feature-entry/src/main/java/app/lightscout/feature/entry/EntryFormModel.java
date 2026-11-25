package app.lightscout.feature.entry;

import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.entry.ValidationCode;
import app.lightscout.domain.entry.ValidationField;
import app.lightscout.domain.entry.Violation;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.feature.entry.EntryFormUiState.Direction;
import app.lightscout.feature.entry.EntryFormUiState.Similar;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.ui.core.ValueText;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.WriteKind;
import app.lightscout.usecases.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.EnumMap;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Executor;

/**
 * S-02 Entry form logic without Android types (CP-13, CP-00A R-27, R-51, R-64, R-75, R-83), JVM-tested with fakes.
 *
 * <ul>
 *   <li>Validation is IF-06 {@link EntryRules#validate} (value on change; time on picker confirm and at Save, R-75);
 *       Bolus text is parsed by {@link EntryRules#parseInsulinMilli} (R-91).</li>
 *   <li>Identifier (GIR-3): create mode calls {@link EntryWriter#newDraft()} once per form session and keeps the
 *       identifier in the {@link StateStore}; every retry and every restore after process death reuses it. Edit mode
 *       uses the entry id.</li>
 *   <li>„Save in flight” is stored before the request; a model rebuilt with that flag set (process death) shows
 *       {@code s02_error_unsure_saved} (CP-13 §7.7).</li>
 *   <li>Outcomes → C-S02-11 copy per CP-00A R-51 ({@link #failureOf}); after PRECONDITION_412 / GONE_404_410 /
 *       NOT_EDITABLE, or a failed {@code loadForEdit}, Save and Delete stay disabled (R-75).</li>
 *   <li>Online only (DS:D-011): offline disables Save and Delete with the offline text; no queue, no automatic retry
 *       (C-4).</li>
 * </ul>
 * IF-23 callbacks arrive on {@code ioExecutor} and are moved to {@code main}. Main thread only otherwise. Never logs
 * values (SR-07).
 */
public final class EntryFormModel {

    /** C-S02-11 copy (mapped to keys by {@link EntryCopy}). */
    public enum Message {
        /** Hidden. */
        NONE,
        /** {@code s02_offline_create}. */
        OFFLINE_CREATE,
        /** {@code s02_offline_edit}. */
        OFFLINE_EDIT,
        /** {@code s02_error_ns_not_responding}. */
        NS_NOT_RESPONDING,
        /** {@code s02_error_token_rejected}. */
        TOKEN_REJECTED,
        /** {@code s02_error_rejected} (DCR-04.1). */
        REJECTED,
        /** {@code s02_error_conflict}. */
        CONFLICT,
        /** {@code s02_error_unsure_saved}. */
        UNSURE_SAVED
    }

    /** Failure of the last request (persisted). */
    enum Failure {
        NONE, OFFLINE, NS_NOT_RESPONDING, TOKEN_REJECTED, REJECTED, CONFLICT, UNSURE_SAVED
    }

    /**
     * One-time UI event.
     *
     * @param kind   what to do
     * @param result for {@link Kind#FINISH}: the S-01 Snackbar message (R-38); otherwise {@code null}
     */
    public record Event(Kind kind, UiMessage result) {

        /** Event kinds. */
        public enum Kind {
            /** Pop to S-01 with a result (save / delete succeeded). */
            FINISH,
            /** Close without a result (nothing entered, or discard confirmed). */
            CLOSE,
            /** Back hides a visible IME without entering the close guard (R-92). */
            HIDE_IME
        }
    }

    /** Receives state and events (the ViewModel). */
    public interface Listener {

        /**
         * New render state.
         *
         * @param s the state
         */
        void onState(EntryFormUiState s);

        /**
         * One-time event.
         *
         * @param e the event
         */
        void onEvent(Event e);
    }

    static final String KEY_IDENTIFIER = "s02.identifier";
    static final String KEY_TYPE = "s02.type";
    static final String KEY_VALUE = "s02.value";
    static final String KEY_TIME = "s02.time";
    static final String KEY_TIME_ERROR = "s02.timeError";
    static final String KEY_LOADED = "s02.loaded";
    static final String KEY_BASE_VALUE = "s02.baseValue";
    static final String KEY_BASE_TIME = "s02.baseTime";
    static final String KEY_SRV_MODIFIED = "s02.srvModified";
    static final String KEY_IN_FLIGHT = "s02.inFlight";
    static final String KEY_FAILURE = "s02.failure";
    static final String KEY_PICKER_DATE = "s02.pickerDate";
    static final String KEY_DISCARD = "s02.discardDialog";
    static final String KEY_DELETE = "s02.deleteDialog";

    private final String entryId;
    private final EntryMode mode;
    private final EntryType initialType;
    private final EnumMap<EntryType, String> drafts = new EnumMap<>(EntryType.class);
    private final EnumMap<EntryType, String> baseline = new EnumMap<>(EntryType.class);
    private EntryType baseType;
    private final EntryWriter writer;
    private final EntryRules rules;
    private final Clock clock;
    private final StateStore store;
    private final Executor main;

    private Listener listener;
    private boolean started;
    private boolean cleared;
    private boolean finished;
    private boolean online;

    private String identifier;
    private EntryType type;
    private String valueText = "";
    private int valueFieldVersion;
    /** D-147: Done / Save was pressed on an invalid value, so the error is shown even for an incomplete entry. */
    private boolean valueErrorForced;
    private Instant time;
    private ValidationCode timeError;
    private boolean loaded;
    private boolean loading;
    private String baseValue = "";
    private Instant baseTime;
    private Long srvModified;
    private boolean saving;
    private boolean deleting;
    private Failure failure = Failure.NONE;
    private LocalDate pickerDate;
    private boolean discardDialog;
    private boolean deleteDialog;
    private int similarSeq;
    private Similar similar;

    /**
     * Creates the model (nothing happens before {@link #start()}).
     *
     * @param entryId Safe Args {@code entryId}: {@code null} = create, otherwise edit (CP-00A R-38)
     * @param writer  IF-23
     * @param rules   IF-06
     * @param clock   IF-15
     * @param store   saved state
     * @param main    main-thread executor for IF-23 callbacks
     * @param online  {@code snapshot.online} at creation
     */
    public EntryFormModel(String entryId, EntryWriter writer, EntryRules rules, Clock clock, StateStore store,
                          Executor main, boolean online) {
        this(entryId, null, writer, rules, clock, store, main, online);
    }

    public EntryFormModel(String entryId, EntryType initialType, EntryWriter writer, EntryRules rules, Clock clock,
                          StateStore store, Executor main, boolean online) {
        if (entryId == null ? !loggable(initialType) : entryId.isEmpty() || initialType != null) {
            throw new IllegalArgumentException("mode-appropriate entry argument required");
        }
        this.entryId = entryId;
        this.mode = entryId == null ? EntryMode.CREATE : EntryMode.EDIT;
        this.initialType = initialType;
        this.writer = Objects.requireNonNull(writer, "writer");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.store = Objects.requireNonNull(store, "store");
        this.main = Objects.requireNonNull(main, "main");
        this.online = online;
    }

    /**
     * Sets the listener (the ViewModel).
     *
     * @param listener the listener
     */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Restores the saved state (or starts a new session), loads the entry in edit mode and publishes. Idempotent. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        restore();
        if (mode == EntryMode.EDIT && !loaded && failure != Failure.CONFLICT) {
            load();
        } else {
            querySimilar();
        }
        publish();
    }

    private void restore() {
        if (mode == EntryMode.EDIT) {
            identifier = entryId;
        } else {
            identifier = store.getString(KEY_IDENTIFIER);
            if (identifier == null) {
                identifier = writer.newDraft().identifier();   // once per form session (GIR-3)
            }
        }
        boolean restored = store.contains(KEY_TYPE);
        type = restored ? parseType(store.getString(KEY_TYPE)) : initialType;
        baseType = restored ? parseType(store.getString("s02.baseType")) : initialType;
        for (EntryType t : List.of(EntryType.CARBS, EntryType.BOLUS, EntryType.BG_CHECK)) {
            String raw = store.getString("s02.raw." + t.name());
            String base = store.getString("s02.base." + t.name());
            drafts.put(t, raw == null ? defaultValue(t) : raw);
            baseline.put(t, base == null ? defaultValue(t) : base);
        }
        String v = store.getString(KEY_VALUE);
        valueText = needsValue(type) ? drafts.get(type) : "";
        if (restored && v != null && needsValue(type)) {
            valueText = v;
            drafts.put(type, v);
        }
        time = parseInstant(store.getString(KEY_TIME));
        timeError = parseCode(store.getString(KEY_TIME_ERROR));
        loaded = store.getBoolean(KEY_LOADED, false);
        String bv = store.getString(KEY_BASE_VALUE);
        baseValue = bv == null ? "" : bv;
        baseTime = parseInstant(store.getString(KEY_BASE_TIME));
        String srv = store.getString(KEY_SRV_MODIFIED);
        srvModified = srv == null ? null : Long.valueOf(srv);
        failure = parseFailure(store.getString(KEY_FAILURE));
        String pd = store.getString(KEY_PICKER_DATE);
        pickerDate = pd == null ? null : LocalDate.ofEpochDay(Long.parseLong(pd));
        discardDialog = store.getBoolean(KEY_DISCARD, false);
        deleteDialog = store.getBoolean(KEY_DELETE, false);
        if (store.getBoolean(KEY_IN_FLIGHT, false)) {
            // process death while a save / delete was in flight: the outcome is unknown (CP-13 §7.7)
            if (failure != Failure.CONFLICT) {
                failure = Failure.UNSURE_SAVED;
            }
        }
        if (online && failure == Failure.OFFLINE) {
            failure = Failure.NONE;
        }
        persist();
    }

    // ------------------------------------------------------------------ edit load

    private void load() {
        loading = true;
        writer.loadForEdit(entryId, r -> main.execute(() -> onLoaded(r)));
    }

    private void onLoaded(Result<EntryDraft> r) {
        if (cleared || finished) {
            return;
        }
        loading = false;
        if (r.ok() && r.value() != null) {
            EntryDraft d = r.value();
            type = d.type();
            valueText = valueTextOf(d.type(), d.value());
            valueFieldVersion++;
            time = d.time();
            baseValue = valueText;
            baseType = type;
            if (needsValue(type)) {
                drafts.put(type, valueText);
                baseline.put(type, valueText);
            }
            baseTime = time;
            srvModified = d.expectedSrvModifiedMs();
            loaded = true;
            querySimilar();
        } else {
            failure = Failure.CONFLICT;   // R-75: fields disabled + s02_error_conflict
        }
        persist();
        publish();
    }

    // ------------------------------------------------------------------ user input

    /**
     * A-S02-1: a type chip was tapped.
     *
     * @param t the type
     */
    public void onTypeSelected(EntryType t) {
        if (!fieldsEnabled() || mode == EntryMode.EDIT || !needsValue(type) || !needsValue(t) || t == type) {
            return;
        }
        type = t;
        valueText = drafts.get(t);
        valueFieldVersion++;
        persist();
        querySimilar();
        publish();
    }

    /**
     * C-S02-06 text changed (validated on change, DS:A-18).
     *
     * @param text the field text
     */
    public void onValueChanged(String text) {
        if (!fieldsEnabled() || !needsValue(type)) {
            return;
        }
        String t = text == null ? "" : text;
        if (t.equals(valueText)) {
            return;
        }
        valueText = t;
        valueErrorForced = false;
        drafts.put(type, t);
        persist();
        publish();
    }

    /** C-S02-08 end icon „Now”: back to „Now” (resolved at Save). */
    public void onTimeNow() {
        if (!fieldsEnabled()) {
            return;
        }
        time = null;
        timeError = null;
        pickerDate = null;
        persist();
        querySimilar();
        publish();
    }

    /**
     * C-S02-09 date picker confirmed; the time picker follows.
     *
     * @param utcSelectionMs {@code MaterialDatePicker} selection (UTC midnight of the chosen day)
     */
    public void onDatePicked(long utcSelectionMs) {
        if (!fieldsEnabled()) {
            return;
        }
        pickerDate = LocalDate.ofEpochDay(Math.floorDiv(utcSelectionMs, Duration.ofDays(1).toMillis()));
        persist();
    }

    /**
     * C-S02-09 time picker confirmed: the time is set and validated (DS:A-19, „on picker confirm”).
     *
     * @param hour   0–23
     * @param minute 0–59
     */
    public void onTimePicked(int hour, int minute) {
        if (!fieldsEnabled()) {
            return;
        }
        LocalDate date = pickerDate != null ? pickerDate : clock.now().atZone(clock.zone()).toLocalDate();
        time = ZonedDateTime.of(LocalDateTime.of(date, LocalTime.of(hour, minute)), clock.zone()).toInstant();
        timeError = timeViolation(time, clock.now());
        pickerDate = null;
        persist();
        querySimilar();
        publish();
    }

    /** A picker was cancelled: the previous value stays (A-S02-3). */
    public void onPickerCancelled() {
        pickerDate = null;
        persist();
    }

    /**
     * Initial values for the time picker: the chosen time, or the current time for „Now”.
     *
     * @return {hour, minute}
     */
    public int[] pickerClock() {
        Instant t = time != null ? time : clock.now();
        LocalTime lt = t.atZone(clock.zone()).toLocalTime();
        return new int[] {lt.getHour(), lt.getMinute()};
    }

    // ------------------------------------------------------------------ save / delete

    /** A-S02-2: „Save” or keyboard „Done”. Ignored unless Save is enabled (GIR-1: never twice in flight). */
    public void onSave() {
        Instant now = clock.now();
        ValidationCode currentTimeError = timeViolation(time, now);
        if (currentTimeError != null) {
            timeError = currentTimeError;
            persist();
            publish();
        }
        if (!saveEnabled()) {
            if (fieldsEnabled() && needsValue(type) && valueViolation() != null) {
                valueErrorForced = true;   // D-147: the user asked to save, so the error is no longer premature
                publish();
            }
            return;
        }
        if (time != null) {
            ValidationCode code = timeViolation(time, now);
            if (code != null) {
                timeError = code;
                persist();
                publish();
                return;
            }
        }
        EntryDraft d = new EntryDraft(identifier, mode, type, needsValue(type) ? parsedValue() : null,
                time == null ? now : time,
                mode == EntryMode.EDIT ? srvModified : null);
        saving = true;
        failure = Failure.NONE;
        store.putBoolean(KEY_IN_FLIGHT, true);
        persist();
        publish();
        writer.save(d, r -> main.execute(() -> onWriteResult(r)));
    }

    /** A-S02-5: „Delete entry” opens C-S02-13. */
    public void onDeleteRequested() {
        if (!deleteEnabled()) {
            return;
        }
        deleteDialog = true;
        persist();
        publish();
    }

    /** C-S02-13 „Delete” (GIR-1: one call however often it is tapped). */
    public void onDeleteConfirmed() {
        if (!deleteDialog) {
            return;
        }
        deleteDialog = false;
        if (!deleteEnabled()) {
            persist();
            publish();
            return;
        }
        deleting = true;
        failure = Failure.NONE;
        store.putBoolean(KEY_IN_FLIGHT, true);
        persist();
        publish();
        writer.delete(identifier, r -> main.execute(() -> onWriteResult(r)));
    }

    /** C-S02-13 „Cancel” or dismissed. */
    public void onDeleteCancelled() {
        deleteDialog = false;
        persist();
        publish();
    }

    private void onWriteResult(Result<WriteResult> r) {
        if (cleared || finished || (!saving && !deleting)) {
            return;
        }
        saving = false;
        deleting = false;
        store.putBoolean(KEY_IN_FLIGHT, false);
        if (r.ok()) {
            finished = true;
            WriteKind kind = r.value() == null ? null : r.value().kind();
            persist();
            publish();
            emit(new Event(Event.Kind.FINISH, resultOf(kind)));
            return;
        }
        failure = failureOf(r);
        persist();
        publish();
    }

    private UiMessage resultOf(WriteKind kind) {
        if (kind == WriteKind.DELETED) {
            return UiMessage.ENTRY_DELETED;
        }
        if (kind == WriteKind.UPDATED || (kind == null && mode == EntryMode.EDIT)) {
            return UiMessage.ENTRY_UPDATED;
        }
        return UiMessage.ENTRY_SAVED;
    }

    /**
     * CP-00A R-51 (S-02 save / delete) with the SS-15 brief notes: STORAGE_ERROR and a pending server change
     * (NS_NOT_RESPONDING) → „not responding”.
     *
     * @param r failed IF-23 result
     * @return the failure
     */
    static Failure failureOf(Result<?> r) {
        FetchOutcome o = r.outcome();
        if (o == FetchOutcome.OK) {
            String d = r.detailCode();
            if (DetailCode.PRECONDITION_412.equals(d) || DetailCode.GONE_404_410.equals(d)
                    || DetailCode.NOT_EDITABLE.equals(d)) {
                return Failure.CONFLICT;
            }
            return Failure.REJECTED;   // REJECTED_400_422: the server answered and refused
        }
        switch (o) {
            case NO_INTERNET:
                return Failure.OFFLINE;
            case TOKEN_REJECTED:
            case PERMISSION_MISSING:
                return Failure.TOKEN_REJECTED;
            case NS_NOT_RESPONDING:
            case NOT_NIGHTSCOUT:
            case SERVER_TOO_OLD:
            case STORAGE_ERROR:
            default:
                return Failure.NS_NOT_RESPONDING;
        }
    }

    // ------------------------------------------------------------------ close / discard

    public enum ClosePath { CANCEL, BACK, SWIPE, OUTSIDE }

    public void onCloseRequested(ClosePath path, boolean imeVisible) {
        Objects.requireNonNull(path, "path");
        if (path == ClosePath.BACK && imeVisible) {
            emit(new Event(Event.Kind.HIDE_IME, null));
            return;
        }
        onCloseRequested();
    }

    /** A-S02-4: „×” or system back. Unsaved changes → C-S02-14; otherwise close. */
    public void onCloseRequested() {
        if (!dismissalEnabled()) {
            return;
        }
        if (isDirty() && !finished) {
            discardDialog = true;
            persist();
            publish();
            return;
        }
        finished = true;
        publish();
        emit(new Event(Event.Kind.CLOSE, null));
    }

    /** C-S02-14 „Discard”. */
    public void onDiscardConfirmed() {
        if (!dismissalEnabled() || !discardDialog) {
            return;
        }
        discardDialog = false;
        finished = true;
        persist();
        publish();
        emit(new Event(Event.Kind.CLOSE, null));
    }

    /** C-S02-14 „Keep editing” or dismissed: the form stays as it is. */
    public void onDiscardCancelled() {
        discardDialog = false;
        persist();
        publish();
    }

    // ------------------------------------------------------------------ status / lifecycle

    /**
     * {@code snapshot.online} changed (IF-21).
     *
     * @param value online
     */
    public void setOnline(boolean value) {
        if (online == value) {
            return;
        }
        online = value;
        if (online && failure == Failure.OFFLINE) {
            failure = Failure.NONE;
            persist();
        }
        publish();
    }

    /** The ViewModel is cleared: later IF-23 callbacks are ignored. */
    public void clear() {
        cleared = true;
        listener = null;
    }

    // ------------------------------------------------------------------ similar entry

    private void querySimilar() {
        final int seq = ++similarSeq;
        similar = null;
        if (type == null || (mode == EntryMode.EDIT && !loaded)) {
            return;
        }
        final Instant reference = time != null ? time : clock.now();
        EntryDraft d = new EntryDraft(identifier, mode, type, null, reference, null);
        writer.similarEntries(d, r -> main.execute(() -> onSimilar(seq, reference, r)));
    }

    private void onSimilar(int seq, Instant reference, Result<List<Entry>> r) {
        if (cleared || finished || seq != similarSeq) {
            return;   // the type or time changed meanwhile
        }
        similar = r.ok() && r.value() != null ? nearest(r.value(), reference) : null;
        publish();
    }

    /** IF-06 {@link EntryRules#findSimilar} (same type, ±10 min, not this entry), then the nearest one. */
    private Similar nearest(List<Entry> list, Instant reference) {
        EntryDraft d = new EntryDraft(identifier, mode, type, null, reference, null);
        Entry best = null;
        Duration bestGap = null;
        for (Entry e : rules.findSimilar(d, list)) {
            Duration gap = Duration.between(e.time(), reference).abs();
            if (bestGap == null || gap.compareTo(bestGap) < 0) {
                best = e;
                bestGap = gap;
            }
        }
        if (best == null) {
            return null;
        }
        long minutes = bestGap.toMinutes();
        Direction dir = minutes == 0 ? Direction.SAME_TIME
                : best.time().isBefore(reference) ? Direction.EARLIER : Direction.LATER;
        return new Similar(best, minutes, dir, EntryRules.sourceLabel(best));
    }

    // ------------------------------------------------------------------ derived state

    private boolean fieldsEnabled() {
        return !finished && !loading && !saving && !deleting && failure != Failure.CONFLICT
                && (mode == EntryMode.CREATE || loaded);
    }

    private boolean saveEnabled() {
        return fieldsEnabled() && failure != Failure.UNSURE_SAVED && !discardDialog && !deleteDialog
                && online && type != null && valueViolation() == null && timeError == null
                && timeViolation(time, clock.now()) == null;
    }

    private boolean deleteEnabled() {
        return mode == EntryMode.EDIT && fieldsEnabled() && failure != Failure.UNSURE_SAVED
                && !discardDialog && online;
    }

    public boolean dismissalEnabled() {
        return !finished && !saving && !deleting;
    }

    private boolean isDirty() {
        if (mode == EntryMode.CREATE) {
            return type != baseType || !drafts.equals(baseline) || !Objects.equals(time, baseTime);
        }
        return loaded && (!valueText.equals(baseValue) || !Objects.equals(time, baseTime));
    }

    /**
     * The error shown under the value: none on the untouched baseline (BUG-37) or empty field, and none while an
     * entry is still incomplete ("1.", "0.02") unless Save / Done was pressed (D-147).
     */
    private ValidationCode valueErrorFor() {
        if (!needsValue(type) || (!valueErrorForced && valueText.equals(baseline.get(type)))) {
            return null;
        }
        boolean empty = valueText.trim().isEmpty();
        if (empty && !valueErrorForced) {
            return null;
        }
        ValidationCode code = valueViolation();
        if (code != null && !valueErrorForced && EntryRules.isIncompleteValue(type, valueText)) {
            return null;
        }
        return code;
    }

    private ValidationCode valueViolation() {
        if (!needsValue(type)) {
            return null;
        }
        EntryDraft d = new EntryDraft(identifier, mode, type, parsedValue(), null, null);
        for (Violation v : rules.validate(d, clock.now()).violations()) {
            if (v.field() == ValidationField.VALUE) {
                return v.code();
            }
        }
        return null;
    }

    private ValidationCode timeViolation(Instant t, Instant now) {
        EntryDraft d = new EntryDraft(identifier, mode, type, null, t, null);
        for (Violation v : rules.validate(d, now).violations()) {
            if (v.field() == ValidationField.TIME) {
                return v.code();
            }
        }
        return null;
    }

    private Integer parsedValue() {
        String text = valueText.trim();
        OptionalInt p = type == EntryType.BOLUS ? EntryRules.parseInsulinMilli(text)
                : EntryRules.parseWholeNumber(text);
        return p.isPresent() ? p.getAsInt() : null;
    }

    private Message message() {
        switch (failure) {
            case CONFLICT:
                return Message.CONFLICT;
            case UNSURE_SAVED:
                return Message.UNSURE_SAVED;
            default:
                break;
        }
        if (!online || failure == Failure.OFFLINE) {
            return mode == EntryMode.CREATE ? Message.OFFLINE_CREATE : Message.OFFLINE_EDIT;
        }
        switch (failure) {
            case NS_NOT_RESPONDING:
                return Message.NS_NOT_RESPONDING;
            case TOKEN_REJECTED:
                return Message.TOKEN_REJECTED;
            case REJECTED:
                return Message.REJECTED;
            default:
                return Message.NONE;
        }
    }

    /**
     * Current render state.
     *
     * @return the state
     */
    public EntryFormUiState state() {
        ValidationCode valueError = valueErrorFor();
        return new EntryFormUiState(mode, type, mode == EntryMode.EDIT, fieldsEnabled(),
                mode == EntryMode.CREATE && type == null, needsValue(type), valueText, valueFieldVersion,
                valueError, time, timeError, loading || saving || deleting, saveEnabled(),
                mode == EntryMode.EDIT, deleteEnabled(), message(), similar, discardDialog, deleteDialog,
                isDirty() && !finished);
    }

    /**
     * The form-session identifier (GIR-3).
     *
     * @return the identifier
     */
    public String identifier() {
        return identifier;
    }

    // ------------------------------------------------------------------ helpers

    private void publish() {
        if (listener != null && started) {
            listener.onState(state());
        }
    }

    private void emit(Event e) {
        if (listener != null) {
            listener.onEvent(e);
        }
    }

    private void persist() {
        store.putString(KEY_IDENTIFIER, identifier);
        store.putString(KEY_TYPE, type == null ? null : type.name());
        store.putString("s02.baseType", baseType == null ? null : baseType.name());
        for (EntryType t : drafts.keySet()) {
            store.putString("s02.raw." + t.name(), drafts.get(t));
            store.putString("s02.base." + t.name(), baseline.get(t));
        }
        store.putString(KEY_VALUE, valueText);
        store.putString(KEY_TIME, time == null ? null : Long.toString(time.toEpochMilli()));
        store.putString(KEY_TIME_ERROR, timeError == null ? null : timeError.name());
        store.putBoolean(KEY_LOADED, loaded);
        store.putString(KEY_BASE_VALUE, baseValue);
        store.putString(KEY_BASE_TIME, baseTime == null ? null : Long.toString(baseTime.toEpochMilli()));
        store.putString(KEY_SRV_MODIFIED, srvModified == null ? null : srvModified.toString());
        store.putString(KEY_FAILURE, failure.name());
        store.putString(KEY_PICKER_DATE, pickerDate == null ? null : Long.toString(pickerDate.toEpochDay()));
        store.putBoolean(KEY_DISCARD, discardDialog);
        store.putBoolean(KEY_DELETE, deleteDialog);
        if (!saving && !deleting) {
            store.putBoolean(KEY_IN_FLIGHT, false);
        }
    }

    static boolean needsValue(EntryType t) {
        return t == EntryType.BG_CHECK || t == EntryType.CARBS || t == EntryType.BOLUS;
    }

    private static boolean loggable(EntryType t) {
        return needsValue(t) || t == EntryType.SITE_CHANGE || t == EntryType.SENSOR_CHANGE;
    }

    /** D-146: every type starts empty; the user types the value (no pre-filled, save-ready number). */
    private static String defaultValue(EntryType t) {
        return "";
    }

    /**
     * Field text of a loaded value: mg/dL and g as integers; exact bolus milli-units as „1”, „1.234”, „1.25” (R-91,
     * {@code .} accepted by the parser).
     *
     * @param t     type
     * @param value value (mg/dL, g or milli-U)
     * @return the text, {@code ""} if none
     */
    static String valueTextOf(EntryType t, Integer value) {
        if (value == null || !needsValue(t)) {
            return "";
        }
        if (t != EntryType.BOLUS) {
            return Integer.toString(value);
        }
        return ValueText.insulinMilliNumber(value.longValue());
    }

    private static EntryType parseType(String s) {
        if (s == null) {
            return null;
        }
        try {
            return EntryType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ValidationCode parseCode(String s) {
        if (s == null) {
            return null;
        }
        try {
            return ValidationCode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Failure parseFailure(String s) {
        if (s == null) {
            return Failure.NONE;
        }
        try {
            return Failure.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Failure.NONE;
        }
    }

    private static Instant parseInstant(String s) {
        return s == null ? null : Instant.ofEpochMilli(Long.parseLong(s));
    }
}
