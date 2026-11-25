package app.lightscout.usecases.entry;

import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.entry.EntryRules;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.MonitorControl;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.port.WriteAck;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.EntryWriter;
import app.lightscout.usecases.WriteKind;
import app.lightscout.usecases.WriteResult;
import app.lightscout.usecases.common.ServerEpoch;
import app.lightscout.usecases.common.SingleFlight;
import app.lightscout.usecases.connection.OriginBoundTokenSource;
import app.lightscout.usecases.sync.IdForms;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * CMP-13 implementation of IF-23 (04 §4.2; 05 IF-38…IF-41, C-3, C-4; GIR-1, GIR-3; DS:D-011, DS:D-016;
 * CP-02 §8.5; CP-00A R-27, R-40, R-50, R-51, R-75).
 *
 * <ul>
 *   <li>Online only: offline → {@code NO_INTERNET} without a request; no outbox, no queue (CON-06).</li>
 *   <li>One write call per user action (plus the IR-50 IF-39 check when the entry is not cached); never retried
 *       automatically (C-4, Q-34). The user retries the
 *       same draft, so the same identifier is sent again (GIR-3; server dedup → 200 + {@code isDeduplication}).</li>
 *   <li>Own entries only for edit / delete (M-4; not cached → IF-39 check first, IR-50); split uploader parts ({@code X#carbs}, {@code X#insulin}) and
 *       non-loggable types (TEMP_BASAL, PUMP_ALERT) are never written.</li>
 *   <li>After a server-confirmed write every stored form of the base id is replaced (Q-39); a cache failure then is
 *       still a success (R-51 — the next tick fetches it).</li>
 * </ul>
 * Work and callbacks on {@code ioExecutor}.
 */
public final class DefaultEntryWriter implements EntryWriter {

    private static final String LIGHTSCOUT = "LightScout";
    private static final Duration SIMILAR_WINDOW = Duration.ofMinutes(10);
    /**
     * IR-52 (DS:A-23, C4-25): a new server is saved but the R-71 task has not re-pointed the monitoring gateway yet;
     * no request is sent to the old server. Transient: CP-13 shows „Nightscout is not responding”, a retry succeeds.
     */
    private static final Result<WriteResult> SERVER_CHANGE_PENDING = Result.fail(FetchOutcome.NS_NOT_RESPONDING, null);

    private final Executor ioExecutor;
    private final NightscoutGateway gateway;
    private final EntryStore entries;
    private final Connectivity connectivity;
    private final MonitorControl monitorControl;
    private final Clock clock;
    private final Logger logger;
    private final EntryRules rules = new EntryRules();
    private final ServerEpoch epoch;
    private final OriginBoundTokenSource serverBinding;
    /** GIR-1 per identifier: identical save / delete joins, a different one runs after it (IR-47, IR-48). */
    private final SingleFlight<WriteResult> flights;

    /**
     * Creates the use case.
     *
     * @param ioExecutor     executor for network and disk work and callbacks
     * @param gateway        IF-07
     * @param entries        IF-09
     * @param connectivity   IF-19 (online-only rule)
     * @param monitorControl IF-62 ({@code ENTRY_WRITTEN} tick after a Site / Sensor change)
     * @param clock          IF-15
     * @param logger         IF-66
     * @param epoch          server epoch shared with the R-71 reset (IR-49: no cache write across a reset)
     * @param serverBinding  the monitoring gateway's token source (IR-52: no write while a server change is pending)
     */
    public DefaultEntryWriter(Executor ioExecutor, NightscoutGateway gateway, EntryStore entries,
                              Connectivity connectivity, MonitorControl monitorControl, Clock clock, Logger logger,
                              ServerEpoch epoch, OriginBoundTokenSource serverBinding) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.connectivity = Objects.requireNonNull(connectivity, "connectivity");
        this.monitorControl = Objects.requireNonNull(monitorControl, "monitorControl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        this.serverBinding = Objects.requireNonNull(serverBinding, "serverBinding");
        this.flights = new SingleFlight<>(ioExecutor, Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE));
    }

    @Override
    public EntryDraft newDraft() {
        return new EntryDraft(UUID.randomUUID().toString(), EntryMode.CREATE, null, null, null, null);
    }

    // ------------------------------------------------------------------ load for edit / similar entries

    @Override
    public void loadForEdit(String identifier, Callback<EntryDraft> cb) {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> cb.onResult(doLoadForEdit(identifier)));
    }

    private Result<EntryDraft> doLoadForEdit(String id) {
        Optional<Entry> cached = cachedEntry(id);
        if (cached.isPresent()) {
            if (!editable(cached.get())) {
                return Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE);
            }
            if (cached.get().srvModifiedMs() != null) {
                return toEditDraft(cached.get());
            }
            // srv_modified_ms NULL after an ack with -1 (R-40): refresh via IF-39
        }
        if (!connectivity.isOnline()) {
            return Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        }
        Result<Entry> r = gateway.get(id);
        if (!r.ok()) {
            return Result.fail(r.outcome(), r.detailCode());
        }
        Entry e = r.value();
        if (e == null) {
            return Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
        }
        // a plain-id GET of a split v1 document returns the carbs part → not editable (M-2, R-50)
        if (!editable(e) || e.srvModifiedMs() == null) {
            return Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        }
        return toEditDraft(e);
    }

    private Result<EntryDraft> toEditDraft(Entry e) {
        if (e.type() == EntryType.BOLUS) {
            Long milli = e.insulinMilli();
            // Imported doses outside the manual range must never wrap, truncate or be silently adjusted.
            if (milli == null || milli < 25L || milli > 25000L) {
                return Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE);
            }
        }
        return Result.ok(new EntryDraft(e.id(), EntryMode.EDIT, e.type(), valueOf(e), e.time(), e.srvModifiedMs()));
    }

    @Override
    public void similarEntries(EntryDraft d, Callback<List<Entry>> cb) {
        Objects.requireNonNull(d, "draft");
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> cb.onResult(doSimilar(d)));
    }

    private Result<List<Entry>> doSimilar(EntryDraft d) {
        if (d.type() == null) {
            return Result.ok(List.of());
        }
        Instant t = d.time() != null ? d.time() : clock.now();
        try {
            // ±10 min inclusive (findSimilar), so the exclusive range end is one millisecond later
            List<Entry> nearby = entries.range(t.minus(SIMILAR_WINDOW), t.plus(SIMILAR_WINDOW).plusMillis(1));
            EntryDraft at = new EntryDraft(d.identifier(), d.mode(), d.type(), d.value(), t, d.expectedSrvModifiedMs());
            return Result.ok(rules.findSimilar(at, nearby));
        } catch (StorageException e) {
            return Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        }
    }

    // ------------------------------------------------------------------ save

    @Override
    public void save(EntryDraft d, Callback<WriteResult> cb) {
        Objects.requireNonNull(d, "draft");
        Objects.requireNonNull(cb, "callback");
        flights.submit(d.identifier(), List.of("save", d), () -> doSave(d), cb);
    }

    private Result<WriteResult> doSave(EntryDraft d) {
        long started = epoch.current();
        Instant now = clock.now();
        Instant time = d.time() != null ? d.time() : now;   // „Now” resolved at Save, validated with it (R-75)
        EntryDraft resolved = new EntryDraft(d.identifier(), d.mode(), d.type(), d.value(), time,
                d.expectedSrvModifiedMs());
        if (!rules.validate(resolved, now).valid()) {
            // CP-13 blocks Save while invalid; reaching here is a defensive refusal, never a request
            return Result.fail(FetchOutcome.OK, DetailCode.REJECTED_400_422);
        }
        boolean edit = d.mode() == EntryMode.EDIT;
        Entry cached = edit ? cachedEntry(d.identifier()).orElse(null) : null;
        Entry entry = toEntry(resolved, cached);
        if (edit && (!editable(entry) || (cached != null && !editable(cached)) || d.expectedSrvModifiedMs() == null)) {
            return Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE);   // M-4: no request
        }
        if (!connectivity.isOnline()) {
            return Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);   // CON-06: no request, no queue
        }
        if (!serverBinding.boundToStoredServer()) {
            return SERVER_CHANGE_PENDING;
        }
        if (edit && cached == null) {
            Result<WriteResult> owned = checkOwnedOnServer(d.identifier());   // IR-50: M-4 also when not cached
            if (owned != null) {
                return owned;
            }
        }
        Result<WriteAck> r = edit ? gateway.update(entry, d.expectedSrvModifiedMs()) : gateway.create(entry);
        if (!r.ok()) {
            logWrite(r.outcome(), r.detailCode());
            return Result.fail(r.outcome(), r.detailCode());   // nothing cached (CON-06); no retry (C-4)
        }
        WriteKind kind = edit ? WriteKind.UPDATED : WriteKind.CREATED;
        long srv = r.value() == null ? -1L : r.value().srvModifiedMs();
        replaceStoredForms(started, withSrvModified(entry, srv < 0 ? null : srv));   // R-40: -1 → NULL
        logger.event(EventCode.ENTRY_WRITE, kind.name());
        requestTickIfConsumable(entry.type());
        return Result.ok(new WriteResult(kind, d.identifier()));
    }

    // ------------------------------------------------------------------ delete

    @Override
    public void delete(String identifier, Callback<WriteResult> cb) {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(cb, "callback");
        flights.submit(identifier, List.of("delete"), () -> doDelete(identifier), cb);
    }

    private Result<WriteResult> doDelete(String id) {
        long started = epoch.current();
        Entry cached = cachedEntry(id).orElse(null);
        boolean allowed = cached != null ? editable(cached) : id.equals(IdForms.base(id));
        if (!allowed) {
            return Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE);   // M-4: no request
        }
        if (!connectivity.isOnline()) {
            return Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        }
        if (!serverBinding.boundToStoredServer()) {
            return SERVER_CHANGE_PENDING;
        }
        EntryType type = cached != null ? cached.type() : null;
        if (cached == null) {
            // IR-50: not cached → IF-39 + ownership check before the delete
            Result<Entry> g = gateway.get(id);
            if (!g.ok()) {
                return Result.fail(g.outcome(), g.detailCode());   // 404 / 410: already gone, nothing cached (repeat-safe)
            }
            if (g.value() == null || !editable(g.value())) {
                return Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE);
            }
            type = g.value().type();
        }
        Result<WriteAck> r = gateway.delete(id);
        if (r.ok()) {
            removeStoredForms(started, id);
            logger.event(EventCode.ENTRY_WRITE, WriteKind.DELETED.name());
            requestTickIfConsumable(type);
            return Result.ok(new WriteResult(WriteKind.DELETED, id));
        }
        logWrite(r.outcome(), r.detailCode());
        if (r.outcome() == FetchOutcome.OK && DetailCode.GONE_404_410.equals(r.detailCode())) {
            removeStoredForms(started, id);   // already gone on the server: remove locally and report the conflict (repeat-safe)
        }
        return Result.fail(r.outcome(), r.detailCode());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * IR-50: an EDIT of an entry that is not cached is checked on the server first (IF-39): it must exist and be an own,
     * editable LightScout entry. Returns the refusal, or {@code null} when the edit may proceed.
     */
    private Result<WriteResult> checkOwnedOnServer(String id) {
        Result<Entry> g = gateway.get(id);
        if (!g.ok()) {
            return Result.fail(g.outcome(), g.detailCode());
        }
        if (g.value() == null || !editable(g.value())) {
            return Result.fail(FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        }
        return null;
    }

    /** Own LightScout treatment of a loggable type (never TEMP_BASAL / PUMP_ALERT, never a split part). */
    private boolean editable(Entry e) {
        return rules.isEditable(e) && isLoggable(e.type());
    }

    private static boolean isLoggable(EntryType t) {
        return t == EntryType.BG_CHECK || t == EntryType.CARBS || t == EntryType.BOLUS
                || t == EntryType.SITE_CHANGE || t == EntryType.SENSOR_CHANGE;
    }

    private static Integer valueOf(Entry e) {
        if (e.type() == null) {
            return null;
        }
        return switch (e.type()) {
            case BG_CHECK -> e.glucose();
            case CARBS -> e.carbs();
            case BOLUS -> Math.toIntExact(e.insulinMilli());
            default -> null;
        };
    }

    /**
     * Builds the treatment to send: own, TREATMENTS, the client identifier, exact insulin in milli-units. Payload fields
     * ({@code app}, {@code enteredBy}, {@code date}, {@code created_at}, {@code utcOffset}, {@code eventType}) are
     * built by CP-03 (ADR-038); {@code app} / {@code enteredBy} are set here only for the cached copy.
     */
    private static Entry toEntry(EntryDraft d, Entry cached) {
        EntryType t = d.type();
        Integer v = d.value();
        return new Entry(d.identifier(), EntryOrigin.TREATMENTS, t, d.time(),
                t == EntryType.BG_CHECK ? v : null,
                t == EntryType.CARBS ? v : null,
                t == EntryType.BOLUS ? (v + 5) / 10 : null,
                t == EntryType.BOLUS ? v.longValue() : null,
                null, null,
                cached != null ? cached.text() : null,
                LIGHTSCOUT, LIGHTSCOUT,
                cached != null ? cached.eventTypeRaw() : null,
                true,
                cached != null ? cached.srvModifiedMs() : null);
    }

    private static Entry withSrvModified(Entry e, Long srv) {
        return new Entry(e.id(), e.origin(), e.type(), e.time(), e.glucose(), e.carbs(), e.insulinCenti(),
                e.insulinMilli(), e.basalRateMilli(), e.durationMin(), e.text(), e.enteredBy(), e.app(),
                e.eventTypeRaw(), e.own(), srv);
    }

    private Optional<Entry> cachedEntry(String id) {
        try {
            return entries.get(id);
        } catch (StorageException e) {
            return Optional.empty();
        }
    }

    /**
     * Q-39: replace every stored form of the base id with the confirmed entry; dropped when a server reset happened
     * since the operation started (IR-49).
     */
    private void replaceStoredForms(long started, Entry e) {
        String base = IdForms.base(e.id());
        try {
            epoch.writeIfCurrent(started, () -> {
                for (String form : IdForms.forms(base)) {
                    if (!form.equals(e.id())) {
                        entries.delete(form);
                    }
                }
                entries.upsert(List.of(e));
            });
        } catch (StorageException ex) {
            // server-confirmed: still a success (R-51); the next tick fetches it
        }
    }

    private void removeStoredForms(long started, String id) {
        try {
            epoch.writeIfCurrent(started, () -> {
                for (String form : IdForms.forms(IdForms.base(id))) {
                    entries.delete(form);
                }
            });
        } catch (StorageException ex) {
            // the next tick's set difference removes it
        }
    }

    /** R-75: counters and reminders update after a Site / Sensor change; other types need no tick. */
    private void requestTickIfConsumable(EntryType t) {
        if (t == EntryType.SITE_CHANGE || t == EntryType.SENSOR_CHANGE) {
            monitorControl.requestTick(TickReason.ENTRY_WRITTEN);
        }
    }

    private void logWrite(FetchOutcome outcome, String detail) {
        logger.event(EventCode.ENTRY_WRITE, detail != null ? detail : outcome.name());
    }
}
