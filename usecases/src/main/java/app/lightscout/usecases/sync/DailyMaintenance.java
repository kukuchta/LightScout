package app.lightscout.usecases.sync;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.DeviceStatusStore;
import app.lightscout.domain.port.EntryStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.NotifiedAlertStore;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.ReadingStore;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.usecases.common.ServerPolicy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * WF-12 daily maintenance inside the first tick after 03:00 local (CP-02 §8.2; CP-00A R-65, R-67): version and
 * permission checks (sticky {@code connectionProblem}), SY-7 capability probe, SY-4 reconcile, eviction.
 * Each step is independent; a failure is retried next day and never changes the tick outcome.
 */
final class DailyMaintenance {

    /** WF-12 local start time. */
    static final LocalTime START = LocalTime.of(3, 0);
    /** LightScout's own documents are never used for the probe. */
    static final String OWN_APP = "LightScout";

    private final NightscoutGateway gateway;
    private final ConnectionStore connectionStore;
    private final ReadingStore readings;
    private final EntryStore entries;
    private final DeviceStatusStore deviceStatus;
    private final NotifiedAlertStore notifiedAlerts;
    private final ChangeSync changeSync;
    private final Logger logger;

    DailyMaintenance(NightscoutGateway gateway, ConnectionStore connectionStore, ReadingStore readings,
                     EntryStore entries, DeviceStatusStore deviceStatus, NotifiedAlertStore notifiedAlerts,
                     ChangeSync changeSync, Logger logger) {
        this.gateway = gateway;
        this.connectionStore = connectionStore;
        this.readings = readings;
        this.entries = entries;
        this.deviceStatus = deviceStatus;
        this.notifiedAlerts = notifiedAlerts;
        this.changeSync = changeSync;
        this.logger = logger;
    }

    /** Due when no maintenance ran since the most recent 03:00 local ≤ now (also on the first tick ever). */
    static boolean isDue(Instant now, ZoneId zone, Instant last) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime boundary = local.toLocalDate().atTime(START).atZone(zone);
        if (local.isBefore(boundary)) {
            boundary = local.toLocalDate().minusDays(1).atTime(START).atZone(zone);
        }
        return last == null || last.isBefore(boundary.toInstant());
    }

    /** Runs every step. */
    void run(TickContext t) {
        t.lastDailyMaintenance = t.now;
        checkServer(t);
        probe(t, NsCollection.ENTRIES);
        probe(t, NsCollection.TREATMENTS);
        reconcile(t);
        evict(t);
        logger.event(EventCode.DAILY_CHECK, t.connectionProblem == null ? "OK" : t.connectionProblem.name());
    }

    /** Version re-check on every tick while {@code SERVER_TOO_OLD} is sticky („also after any hint”). */
    void recheckVersion(TickContext t) {
        Check v = versionCheck();
        if (v.known()) {
            if (v.problem() != null) {
                t.connectionProblem = v.problem();
            } else if (t.connectionProblem == FetchOutcome.SERVER_TOO_OLD) {
                t.connectionProblem = null;
            }
        }
    }

    /** Result of one check: {@code known} = the server answered; {@code problem} = null when it passed. */
    private record Check(boolean known, FetchOutcome problem) {
    }

    private void checkServer(TickContext t) {
        Check version = versionCheck();
        Check permissions = permissionCheck();
        if (version.known() && version.problem() != null) {
            t.connectionProblem = version.problem();
        } else if (permissions.known() && permissions.problem() != null) {
            t.connectionProblem = permissions.problem();
        } else if (version.known() && permissions.known()) {
            t.connectionProblem = null;
        }
    }

    private Check versionCheck() {
        Optional<String> baseUrl;
        try {
            baseUrl = connectionStore.baseUrl();
        } catch (StorageException e) {
            return new Check(false, null);
        }
        if (baseUrl.isEmpty()) {
            return new Check(false, null);
        }
        Result<ServerInfo> r = gateway.version(baseUrl.get());
        if (!r.ok()) {
            return r.outcome() == FetchOutcome.NOT_NIGHTSCOUT ? new Check(true, FetchOutcome.NOT_NIGHTSCOUT)
                    : new Check(false, null);
        }
        Result<ServerInfo> policy = ServerPolicy.checkServer(r.value());
        if (policy.ok()) {
            return new Check(true, null);
        }
        // A real Nightscout without API v3 is an old server for the Home chip (S-06 keeps its own copy).
        return new Check(true, DetailCode.NOT_API_V3.equals(policy.detailCode())
                ? FetchOutcome.SERVER_TOO_OLD : policy.outcome());
    }

    private Check permissionCheck() {
        Result<Permissions> r = gateway.permissions();
        if (!r.ok()) {
            return new Check(false, null);
        }
        Result<Permissions> policy = ServerPolicy.checkPermissions(r.value());
        if (policy.ok()) {
            return new Check(true, null);
        }
        return new Check(true, DetailCode.MISSING_WRITE.equals(policy.detailCode())
                ? FetchOutcome.PERMISSION_WRITE_MISSING : policy.outcome());
    }

    /**
     * SY-7 capability probe: newest cached document not written by LightScout with event time {@code t};
     * {@code historyCapable} only if {@code history/{t − 1}} returns it; a failed call leaves the flag unchanged.
     */
    private void probe(TickContext t, NsCollection c) {
        TickContext.CursorDraft cur = t.cursor(c);
        String id;
        Instant time;
        try {
            if (c == NsCollection.ENTRIES) {
                Optional<Reading> r = readings.latestNotAfter(t.now.plus(ChangeSync.FUTURE_TOLERANCE));
                id = r.map(Reading::id).orElse(null);
                time = r.map(Reading::time).orElse(null);
            } else {
                Entry newest = null;
                for (Entry e : entries.range(t.now.minus(ChangeSync.CACHE_WINDOW),
                        t.now.plus(ChangeSync.FUTURE_TOLERANCE))) {
                    if (e.origin() == EntryOrigin.TREATMENTS && !e.own() && !OWN_APP.equals(e.app())
                            && e.time() != null && (newest == null || e.time().isAfter(newest.time()))) {
                        newest = e;
                    }
                }
                id = newest == null ? null : newest.id();
                time = newest == null ? null : newest.time();
            }
        } catch (StorageException e) {
            return;
        }
        if (id == null || time == null) {
            cur.historyCapable = false;
            cur.historyCursorMs = null;
            return;
        }
        Result<Page<Change>> r = gateway.history(c, time.toEpochMilli() - 1, ChangeSync.HISTORY_LIMIT);
        if (!r.ok()) {
            return;
        }
        boolean found = false;
        List<Change> items = r.value() == null ? List.of() : r.value().items();
        for (Change ch : items) {
            if (matches(ch, id)) {
                found = true;
                break;
            }
        }
        if (found && !cur.historyCapable) {
            cur.historyCapable = true;
            cur.historyCursorMs = cur.lastModifiedSeen != null ? cur.lastModifiedSeen
                    : t.now.minus(ChangeSync.HOT_WINDOW).toEpochMilli();
        } else if (!found) {
            cur.historyCapable = false;
            cur.historyCursorMs = null;
        }
    }

    private static boolean matches(Change ch, String id) {
        if (IdForms.base(ch.id()).equals(IdForms.base(id))) {
            return true;
        }
        return (ch.entry() != null && id.equals(ch.entry().id()))
                || (ch.reading() != null && id.equals(ch.reading().id()));
    }

    /**
     * SY-4 reconcile over the filled range {@code [max(completeFrom, now − 31 d), now)} (architect ruling on SS-05 A-16: also while
     * the SY-8 backfill is incomplete). {@code completeFrom} is taken as read at tick start, so the first-fill tick
     * (no range yet) does not reconcile.
     */
    private void reconcile(TickContext t) {
        Instant floor = t.now.minus(ChangeSync.CACHE_WINDOW);
        Instant entriesFrom = reconcileFrom(t, NsCollection.ENTRIES, floor);
        if (entriesFrom != null) {
            ChangeSync.WindowResult w = changeSync.entriesWindow(t, entriesFrom, t.now);
            if (w.complete()) {
                t.cursor(NsCollection.ENTRIES).lastReconcile = t.now;
            }
            if (w.failure() != null) {
                return;
            }
        }
        Instant treatmentsFrom = reconcileFrom(t, NsCollection.TREATMENTS, floor);
        if (treatmentsFrom != null) {
            ChangeSync.WindowResult w = changeSync.treatmentsWindow(t, treatmentsFrom, t.now);
            if (w.complete()) {
                t.cursor(NsCollection.TREATMENTS).lastReconcile = t.now;
            }
        }
    }

    private static Instant reconcileFrom(TickContext t, NsCollection c, Instant floor) {
        var prev = t.prevCursor(c);
        Instant completeFrom = prev == null ? null : prev.completeFrom();
        if (completeFrom == null || !completeFrom.isBefore(t.now)) {
            return null;
        }
        return completeFrom.isBefore(floor) ? floor : completeFrom;
    }

    /** R-65: daily eviction {@code time < now − 31 d} on IF-08 / IF-09 / IF-10 and IF-64. */
    private void evict(TickContext t) {
        Instant cutoff = t.now.minus(ChangeSync.CACHE_WINDOW);
        List<Runnable> steps = List.of(
                () -> readings.evictBefore(cutoff),
                () -> entries.evictBefore(cutoff),
                () -> deviceStatus.evictBefore(cutoff),
                () -> notifiedAlerts.evictBefore(cutoff));
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (StorageException e) {
                // retried next day (WF-12); the store already logged STORAGE_ERROR (R-13)
            }
        }
    }
}
