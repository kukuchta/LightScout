package app.lightscout.usecases.entry;

import app.lightscout.domain.entry.EntryDraft;
import app.lightscout.domain.entry.EntryMode;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.WriteAck;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.WriteKind;
import app.lightscout.usecases.WriteResult;
import app.lightscout.usecases.fakes.Cp02bFakes;
import app.lightscout.usecases.fakes.Fakes;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static app.lightscout.usecases.fakes.Fakes.ownTreatment;
import static app.lightscout.usecases.fakes.Fakes.treatment;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** EW-1…EW-8, GIR-1, GIR-3, R-27 / R-40 / R-50 / R-75, Q-39 (CP-02 §9). */
public class EntryWriterTest {

    private static final Instant NOW = Harness.NOW;

    private Cp02bFakes.FakeGateway gateway;
    private Fakes.FakeEntryStore entries;
    private Fakes.FakeConnectivity connectivity;
    private Fakes.FakeMonitorControl monitorControl;
    private Fakes.FakeLogger logger;
    private Fakes.QueueExecutor io;
    private DefaultEntryWriter writer;
    private app.lightscout.usecases.common.ServerEpoch epoch;
    private Cp02bFakes.FakeConnectionStore connection;
    private app.lightscout.usecases.connection.OriginBoundTokenSource binding;

    @Before
    public void setUp() {
        gateway = new Cp02bFakes.FakeGateway();
        entries = new Fakes.FakeEntryStore();
        connectivity = new Fakes.FakeConnectivity();
        monitorControl = new Fakes.FakeMonitorControl();
        logger = new Fakes.FakeLogger();
        io = new Fakes.QueueExecutor();
        epoch = new app.lightscout.usecases.common.ServerEpoch();
        connection = new Cp02bFakes.FakeConnectionStore();
        connection.url = "https://ns.example.org";
        connection.token = "tok".toCharArray();
        binding = new app.lightscout.usecases.connection.OriginBoundTokenSource(connection);
        binding.bind(connection.url);   // monitoring gateway configured for the stored server
        writer = new DefaultEntryWriter(io, gateway, entries, connectivity, monitorControl,
                new Fakes.FakeClock(NOW), logger, epoch, binding);
    }

    private EntryDraft carbs(EntryDraft base, int grams, Instant time) {
        return new EntryDraft(base.identifier(), EntryMode.CREATE, EntryType.CARBS, grams, time, null);
    }

    private Result<WriteResult> save(EntryDraft d) {
        Cp02bFakes.Recorder<WriteResult> r = new Cp02bFakes.Recorder<>();
        writer.save(d, r);
        io.runAll();
        return r.only();
    }

    private Result<WriteResult> delete(String id) {
        Cp02bFakes.Recorder<WriteResult> r = new Cp02bFakes.Recorder<>();
        writer.delete(id, r);
        io.runAll();
        return r.only();
    }

    private Result<EntryDraft> loadForEdit(String id) {
        Cp02bFakes.Recorder<EntryDraft> r = new Cp02bFakes.Recorder<>();
        writer.loadForEdit(id, r);
        io.runAll();
        return r.only();
    }

    private static void assertFailure(Result<?> r, FetchOutcome o, String detail) {
        assertFalse(r.ok());
        assertEquals(o, r.outcome());
        assertEquals(detail, r.detailCode());
    }

    // ------------------------------------------------------------------ EW-1 / GIR-3

    @Test
    public void ew1_newDraftTwice_twoUuidV4Identifiers() {
        EntryDraft a = writer.newDraft();
        EntryDraft b = writer.newDraft();
        assertNotEquals(a.identifier(), b.identifier());
        assertEquals(4, UUID.fromString(a.identifier()).version());
        assertEquals(EntryMode.CREATE, a.mode());
        assertNull(a.type());
        assertTrue(io.queue.isEmpty());   // memory only
    }

    @Test
    public void ew1_gir3_lostResponse_retryOfSameDraft_sendsSameIdentifier_dedupIsSuccess() {
        EntryDraft d = carbs(writer.newDraft(), 20, NOW.minusSeconds(60));
        gateway.create = e -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        assertFailure(save(d), FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        gateway.create = e -> Result.ok(new WriteAck(e.id(), 5_000L, true));   // 200 + isDeduplication
        Result<WriteResult> r = save(d);
        assertEquals(new WriteResult(WriteKind.CREATED, d.identifier()), r.value());
        assertEquals(2, gateway.created.size());
        assertEquals(d.identifier(), gateway.created.get(0).id());
        assertEquals(d.identifier(), gateway.created.get(1).id());
    }

    // ------------------------------------------------------------------ EW-2

    @Test
    public void ew2_create201_upsertedLocallyWithSrvModified() {
        EntryDraft d = new EntryDraft(writer.newDraft().identifier(), EntryMode.CREATE, EntryType.BOLUS, 1500,
                NOW.minusSeconds(120), null);
        Result<WriteResult> r = save(d);
        assertEquals(new WriteResult(WriteKind.CREATED, d.identifier()), r.value());
        Entry sent = gateway.created.get(0);
        assertEquals(EntryOrigin.TREATMENTS, sent.origin());
        assertTrue(sent.own());
        assertEquals(Integer.valueOf(150), sent.insulinCenti());
        assertEquals(Long.valueOf(1500), sent.insulinMilli());
        assertNull(sent.carbs());
        Entry cached = entries.rows.get(d.identifier());
        assertEquals(Long.valueOf(5_000L), cached.srvModifiedMs());
        assertEquals("LightScout", cached.app());
        assertTrue(logger.events.contains("ENTRY_WRITE:CREATED"));
    }

    @Test
    public void r91_createReloadEditUpdateRetainsExactMilli() {
        for (int milli : new int[] {25, 1234, 25000}) {
            EntryDraft d = new EntryDraft(writer.newDraft().identifier(), EntryMode.CREATE, EntryType.BOLUS,
                    milli, null, null);
            assertTrue(save(d).ok());
            Entry sent = gateway.created.get(gateway.created.size() - 1);
            assertEquals(Long.valueOf(milli), sent.insulinMilli());
            assertEquals(NOW, sent.time());
            assertEquals(Long.valueOf(milli), entries.get(d.identifier()).orElseThrow().insulinMilli());
            EntryDraft edit = loadForEdit(d.identifier()).value();
            assertEquals(Integer.valueOf(milli), edit.value());
            assertEquals(Long.valueOf(5000), edit.expectedSrvModifiedMs());
            assertTrue(save(edit).ok());
            Entry updated = (Entry) gateway.updated.get(gateway.updated.size() - 1)[0];
            assertEquals(Long.valueOf(milli), updated.insulinMilli());
            assertEquals(Long.valueOf(6000), entries.get(d.identifier()).orElseThrow().srvModifiedMs());
            assertEquals(Integer.valueOf(milli), loadForEdit(d.identifier()).value().value());
        }
    }

    @Test
    public void r91_legacyAndImportedEditUseMilliAndRejectOutOfRangeBeforeNarrowing() {
        Entry legacy = new Entry("legacy", EntryOrigin.TREATMENTS, EntryType.BOLUS, NOW,
                null, null, 123, null, null, null, "LightScout", "LightScout", "Bolus", true, 1000L);
        entries.upsert(List.of(legacy));
        assertEquals(Integer.valueOf(1230), loadForEdit("legacy").value().value());
        assertTrue(save(loadForEdit("legacy").value()).ok());
        for (long milli : new long[] {1, 24, 1234, 25001, 4_294_968_530L, Long.MAX_VALUE}) {
            Entry imported = new Entry("imported", EntryOrigin.TREATMENTS, EntryType.BOLUS, NOW,
                    null, null, 123, milli, null, null, "note", "LightScout", "LightScout", "Bolus", true, 1000L);
            entries.upsert(List.of(imported));
            Result<EntryDraft> cached = loadForEdit("imported");
            if (milli == 1234) {
                assertEquals(Integer.valueOf(1234), cached.value().value());
                assertTrue(save(cached.value()).ok());
                assertEquals(Long.valueOf(1234), ((Entry) gateway.updated.get(1)[0]).insulinMilli());
            } else {
                assertFailure(cached, FetchOutcome.OK, DetailCode.NOT_EDITABLE);
            }
            entries.rows.remove("imported");
            gateway.get = id -> Result.ok(imported);
            Result<EntryDraft> remote = loadForEdit("imported");
            if (milli == 1234) {
                assertEquals(Integer.valueOf(1234), remote.value().value());
            } else {
                assertFailure(remote, FetchOutcome.OK, DetailCode.NOT_EDITABLE);
            }
        }
        assertEquals(2, gateway.updated.size());
    }

    @Test
    public void ew2_failures_nothingCached_codesPassedThrough() {
        EntryDraft d = carbs(writer.newDraft(), 20, NOW);
        gateway.create = e -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        assertFailure(save(d), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        gateway.create = e -> Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        assertFailure(save(d), FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        gateway.create = e -> Result.fail(FetchOutcome.OK, DetailCode.REJECTED_400_422);
        assertFailure(save(d), FetchOutcome.OK, DetailCode.REJECTED_400_422);
        gateway.create = e -> Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);   // lost mid-request
        assertFailure(save(d), FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        assertTrue(entries.rows.isEmpty());
        assertEquals(0, entries.upsertCalls);
        assertTrue(logger.events.contains("ENTRY_WRITE:AUTH_401"));
    }

    @Test
    public void r40_ackWithoutSrvModified_storedAsNull() {
        EntryDraft d = new EntryDraft(writer.newDraft().identifier(), EntryMode.CREATE, EntryType.BOLUS,
                1234, NOW, null);
        gateway.create = e -> Result.ok(new WriteAck(e.id(), -1L, false));
        assertTrue(save(d).ok());
        Entry cached = entries.rows.get(d.identifier());
        assertNull(cached.srvModifiedMs());
        assertEquals(Long.valueOf(1234), cached.insulinMilli());
        gateway.get = id -> Result.ok(new Entry(cached.id(), cached.origin(), cached.type(), cached.time(),
                cached.glucose(), cached.carbs(), cached.insulinCenti(), cached.insulinMilli(), null, null,
                cached.text(), cached.enteredBy(), cached.app(), cached.eventTypeRaw(), cached.own(), 7000L));
        EntryDraft reloaded = loadForEdit(d.identifier()).value();
        assertEquals(Integer.valueOf(1234), reloaded.value());
        assertEquals(Long.valueOf(7000), reloaded.expectedSrvModifiedMs());
    }

    @Test
    public void r51_cacheFailureAfterServerConfirmedWrite_isSuccess() {
        entries.fail = true;
        Result<WriteResult> r = save(carbs(writer.newDraft(), 20, NOW));
        assertTrue(r.ok());
        assertEquals(WriteKind.CREATED, r.value().kind());
    }

    @Test
    public void r75_nowResolvedAtSave_invalidDraftNeverSent() {
        EntryDraft d = carbs(writer.newDraft(), 20, null);
        assertTrue(save(d).ok());
        assertEquals(NOW, gateway.created.get(0).time());
        EntryDraft invalid = carbs(writer.newDraft(), 0, null);   // carbs 0 → CARBS_RANGE
        assertFailure(save(invalid), FetchOutcome.OK, DetailCode.REJECTED_400_422);
        EntryDraft future = carbs(writer.newDraft(), 20, NOW.plus(Duration.ofMinutes(5)));
        assertFalse(save(future).ok());
        EntryDraft noType = writer.newDraft();
        assertFalse(save(noType).ok());
        assertEquals(1, gateway.created.size());
    }

    // ------------------------------------------------------------------ EW-3 / EW-6 / EW-7 / EW-8

    @Test
    public void ew3_offline_offlineCode_noGatewayCall() {
        connectivity.online = false;
        entries.put(ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS));
        assertFailure(save(carbs(writer.newDraft(), 20, NOW)), FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        assertFailure(save(new EntryDraft("own1", EntryMode.EDIT, EntryType.CARBS, 30, NOW.minusSeconds(600), 1L)),
                FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        assertFailure(delete("own1"), FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void ew6_noAutomaticRetry_exactlyOneCallPerUserAction() {
        gateway.create = e -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        gateway.update = (e, ms) -> Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        gateway.delete = id -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        entries.put(ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS));
        save(carbs(writer.newDraft(), 20, NOW));
        assertEquals(List.of("create"), gateway.calls);
        save(new EntryDraft("own1", EntryMode.EDIT, EntryType.CARBS, 30, NOW.minusSeconds(600), 1L));
        assertEquals(List.of("create", "update"), gateway.calls);
        delete("own1");
        assertEquals(List.of("create", "update", "delete"), gateway.calls);
        assertTrue(io.queue.isEmpty());
        assertTrue(monitorControl.ticks.isEmpty());
    }

    @Test
    public void ew7_siteChangeCreated_requestsTick_carbsDoesNot() {
        save(carbs(writer.newDraft(), 20, NOW));
        assertTrue(monitorControl.ticks.isEmpty());
        save(new EntryDraft(writer.newDraft().identifier(), EntryMode.CREATE, EntryType.SITE_CHANGE, null, NOW, null));
        assertEquals(List.of(TickReason.ENTRY_WRITTEN), monitorControl.ticks);
        save(new EntryDraft(writer.newDraft().identifier(), EntryMode.CREATE, EntryType.SENSOR_CHANGE, null, NOW, null));
        assertEquals(2, monitorControl.ticks.size());
        // a failed write requests nothing
        gateway.create = e -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        save(new EntryDraft(writer.newDraft().identifier(), EntryMode.CREATE, EntryType.SITE_CHANGE, null, NOW, null));
        assertEquals(2, monitorControl.ticks.size());
    }

    @Test
    public void r75_siteChangeDeleted_requestsTick() {
        entries.put(ownTreatment("site1", NOW.minusSeconds(600), EntryType.SITE_CHANGE));
        assertTrue(delete("site1").ok());
        assertEquals(List.of(TickReason.ENTRY_WRITTEN), monitorControl.ticks);
    }

    @Test
    public void ew8_gir1_identicalSaveJoins_deleteOfSameIdRunsAfter_ir47() {
        EntryDraft d = carbs(writer.newDraft(), 20, NOW);
        Cp02bFakes.Recorder<WriteResult> first = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<WriteResult> second = new Cp02bFakes.Recorder<>();
        writer.save(d, first);
        writer.save(d, second);
        Cp02bFakes.Recorder<WriteResult> del = new Cp02bFakes.Recorder<>();
        writer.delete(d.identifier(), del);   // same identifier → also rejected
        io.runAll();
        assertEquals(1, gateway.created.size());
        assertEquals(List.of("create", "delete"), gateway.calls);   // the delete is serialised, not joined (IR-47)
        assertTrue(first.only().ok());
        assertSame(first.only(), second.only());          // joined: the same Result, exactly once
        assertEquals(new WriteResult(WriteKind.DELETED, d.identifier()), del.only().value());
        // accepted again after the first one finished
        assertTrue(save(d).ok());
        assertEquals(2, gateway.created.size());
    }

    // ------------------------------------------------------------------ EW-4 edit

    @Test
    public void ew4_editNonOwn_refusedWithoutCall() {
        entries.put(treatment("up1", NOW.minusSeconds(600), EntryType.CARBS));
        assertFailure(save(new EntryDraft("up1", EntryMode.EDIT, EntryType.CARBS, 30, NOW.minusSeconds(600), 1L)),
                FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        assertFailure(save(new EntryDraft("X#carbs", EntryMode.EDIT, EntryType.CARBS, 30, NOW.minusSeconds(600), 1L)),
                FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        assertFailure(delete("up1"), FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        assertFailure(delete("X#insulin"), FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void ew4_editOwn_updateWithStoredSrvModified_412Conflict_cacheUnchanged() {
        Entry own = ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS);   // srvModified 1
        entries.put(own);
        EntryDraft draft = loadForEdit("own1").value();
        assertEquals(EntryMode.EDIT, draft.mode());
        assertEquals(Long.valueOf(1L), draft.expectedSrvModifiedMs());
        assertEquals(Integer.valueOf(10), draft.value());
        EntryDraft changed = new EntryDraft(draft.identifier(), EntryMode.EDIT, draft.type(), 35, draft.time(),
                draft.expectedSrvModifiedMs());
        gateway.update = (e, ms) -> Result.fail(FetchOutcome.OK, DetailCode.PRECONDITION_412);
        assertFailure(save(changed), FetchOutcome.OK, DetailCode.PRECONDITION_412);
        assertEquals(1L, gateway.updated.get(0)[1]);
        assertEquals(own, entries.rows.get("own1"));
        gateway.update = (e, ms) -> Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
        assertFailure(save(changed), FetchOutcome.OK, DetailCode.GONE_404_410);
        assertEquals(own, entries.rows.get("own1"));
        gateway.update = (e, ms) -> Result.ok(new WriteAck(e.id(), 9_000L, false));
        assertEquals(new WriteResult(WriteKind.UPDATED, "own1"), save(changed).value());
        assertEquals(Integer.valueOf(35), entries.rows.get("own1").carbs());
        assertEquals(Long.valueOf(9_000L), entries.rows.get("own1").srvModifiedMs());
    }

    @Test
    public void q39_writeReplacesEveryStoredFormOfTheBaseId() {
        Entry own = ownTreatment("X", NOW.minusSeconds(600), EntryType.CARBS);
        entries.put(own, treatment("X#carbs", NOW.minusSeconds(600), EntryType.CARBS),
                treatment("X#insulin", NOW.minusSeconds(600), EntryType.BOLUS));
        save(new EntryDraft("X", EntryMode.EDIT, EntryType.CARBS, 40, NOW.minusSeconds(600), 1L));
        assertEquals(List.of("X"), List.copyOf(entries.rows.keySet()));
        assertEquals(Integer.valueOf(40), entries.rows.get("X").carbs());
    }

    // ------------------------------------------------------------------ EW-5 delete

    @Test
    public void ew5_delete204_removedLocally() {
        entries.put(ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS));
        assertEquals(new WriteResult(WriteKind.DELETED, "own1"), delete("own1").value());
        assertFalse(entries.rows.containsKey("own1"));
        assertEquals(List.of("own1"), gateway.deletedIds);
    }

    @Test
    public void ew5_delete404or410_removedLocallyAndConflict_repeatSafe() {
        entries.put(ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS));
        gateway.delete = id -> Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
        assertFailure(delete("own1"), FetchOutcome.OK, DetailCode.GONE_404_410);
        assertFalse(entries.rows.containsKey("own1"));
        // repeat: not cached any more → IF-39 check first (IR-50) answers 404 / 410; no second DELETE
        assertFailure(delete("own1"), FetchOutcome.OK, DetailCode.GONE_404_410);
        assertEquals(1, gateway.deletedIds.size());
    }

    @Test
    public void delete_otherFailure_keepsCache() {
        entries.put(ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS));
        gateway.delete = id -> Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        assertFailure(delete("own1"), FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        assertTrue(entries.rows.containsKey("own1"));
    }

    // ------------------------------------------------------------------ loadForEdit (R-27, R-40, R-50)

    @Test
    public void loadForEdit_cacheFirst_noNetworkCall() {
        entries.put(ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS));
        connectivity.online = false;
        assertTrue(loadForEdit("own1").ok());
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void loadForEdit_nonOwnCached_notEditable() {
        entries.put(treatment("up1", NOW.minusSeconds(600), EntryType.CARBS));
        assertFailure(loadForEdit("up1"), FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void loadForEdit_notCached_if39_splitCarbsPartNotEditable_goneAndOffline() {
        gateway.get = id -> Result.ok(treatment(id + "#carbs", NOW.minusSeconds(600), EntryType.CARBS));
        assertFailure(loadForEdit("X"), FetchOutcome.OK, DetailCode.NOT_EDITABLE);   // M-2
        gateway.get = id -> Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
        assertFailure(loadForEdit("X"), FetchOutcome.OK, DetailCode.GONE_404_410);
        gateway.get = id -> Result.ok(ownTreatment(id, NOW.minusSeconds(600), EntryType.BG_CHECK));
        Result<EntryDraft> ok = loadForEdit("own2");
        assertEquals(EntryType.BG_CHECK, ok.value().type());
        assertEquals(Long.valueOf(1L), ok.value().expectedSrvModifiedMs());
        connectivity.online = false;
        assertFailure(loadForEdit("own3"), FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        assertEquals(3, gateway.count("get"));
    }

    @Test
    public void loadForEdit_cachedWithNullSrvModified_refreshedViaIf39() {
        Entry own = ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS);
        entries.put(new Entry(own.id(), own.origin(), own.type(), own.time(), null, 10, null, null, null, null,
                own.enteredBy(), own.app(), null, true, null));
        gateway.get = id -> Result.ok(new Entry(id, EntryOrigin.TREATMENTS, EntryType.CARBS, own.time(), null, 10,
                null, null, null, null, "LightScout", "LightScout", null, true, 7_000L));
        assertEquals(Long.valueOf(7_000L), loadForEdit("own1").value().expectedSrvModifiedMs());
        assertEquals(1, gateway.count("get"));
    }

    // ------------------------------------------------------------------ similar entries (F-10)

    @Test
    public void similarEntries_sameTypeWithin10Min_excludesTheEditedEntry() {
        entries.put(ownTreatment("a", NOW.minus(Duration.ofMinutes(10)), EntryType.CARBS),
                ownTreatment("b", NOW.minus(Duration.ofMinutes(11)), EntryType.CARBS),
                ownTreatment("c", NOW.plus(Duration.ofMinutes(5)), EntryType.BOLUS),
                ownTreatment("self", NOW, EntryType.CARBS));
        Cp02bFakes.Recorder<List<Entry>> r = new Cp02bFakes.Recorder<>();
        writer.similarEntries(new EntryDraft("self", EntryMode.EDIT, EntryType.CARBS, 20, null, 1L), r);   // time = now
        io.runAll();
        assertEquals(List.of("a"), r.only().value().stream().map(Entry::id).toList());
        Cp02bFakes.Recorder<List<Entry>> none = new Cp02bFakes.Recorder<>();
        writer.similarEntries(writer.newDraft(), none);   // no type yet
        io.runAll();
        assertTrue(none.only().value().isEmpty());
        assertTrue(gateway.calls.isEmpty());
    }

    // ------------------------------------------------------------------ round 2 (IR-47, IR-49, IR-50)

    @Test
    public void ir47_differentDraftSameIdentifier_serialised_bothSent() {
        EntryDraft a = carbs(writer.newDraft(), 20, NOW);
        EntryDraft b = carbs(a, 25, NOW);
        Cp02bFakes.Recorder<WriteResult> ra = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<WriteResult> rb = new Cp02bFakes.Recorder<>();
        writer.save(a, ra);
        writer.save(b, rb);
        io.runAll();
        assertEquals(2, gateway.created.size());
        assertEquals(Integer.valueOf(20), gateway.created.get(0).carbs());
        assertEquals(Integer.valueOf(25), gateway.created.get(1).carbs());
        assertTrue(ra.only().ok());
        assertTrue(rb.only().ok());
    }

    @Test
    public void ir49_resetWhileWriteInFlight_cacheWriteDropped_resultStillSuccess() {
        gateway.create = e -> {
            epoch.advance(() -> { });   // an R-71 reset runs while the POST is in flight
            return Result.ok(new WriteAck(e.id(), 5_000L, false));
        };
        assertTrue(save(carbs(writer.newDraft(), 20, NOW)).ok());
        assertTrue(entries.rows.isEmpty());
    }

    @Test
    public void ir50_editNotCached_checkedOnServerFirst() {
        gateway.get = id -> Result.ok(treatment(id, NOW.minusSeconds(600), EntryType.CARBS));   // not own
        EntryDraft edit = new EntryDraft("x1", EntryMode.EDIT, EntryType.CARBS, 30, NOW.minusSeconds(600), 1L);
        assertFailure(save(edit), FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        assertEquals(List.of("get"), gateway.calls);
        gateway.get = id -> Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
        assertFailure(save(edit), FetchOutcome.OK, DetailCode.GONE_404_410);
        gateway.get = id -> Result.ok(ownTreatment(id, NOW.minusSeconds(600), EntryType.CARBS));
        assertEquals(WriteKind.UPDATED, save(edit).value().kind());
        assertEquals(List.of("get", "get", "get", "update"), gateway.calls);
    }

    @Test
    public void ir50_deleteNotCached_checkedOnServerFirst() {
        gateway.get = id -> Result.ok(treatment(id, NOW.minusSeconds(600), EntryType.CARBS));   // not own
        assertFailure(delete("x1"), FetchOutcome.OK, DetailCode.NOT_EDITABLE);
        assertTrue(gateway.deletedIds.isEmpty());
        gateway.get = id -> Result.ok(ownTreatment(id, NOW.minusSeconds(600), EntryType.SITE_CHANGE));
        assertEquals(WriteKind.DELETED, delete("x1").value().kind());
        assertEquals(List.of("x1"), gateway.deletedIds);
        assertEquals(List.of(TickReason.ENTRY_WRITTEN), monitorControl.ticks);   // type known from IF-39
    }

    @Test
    public void ir52_newServerSavedButResetPending_noWriteToTheOldServer() {
        entries.put(ownTreatment("own1", NOW.minusSeconds(600), EntryType.CARBS));
        connection.url = "https://new.example.org";   // saved; the R-71 task has not re-bound the gateway yet
        Result<WriteResult> create = save(carbs(writer.newDraft(), 20, NOW));
        assertFailure(create, FetchOutcome.NS_NOT_RESPONDING, null);
        assertFailure(delete("own1"), FetchOutcome.NS_NOT_RESPONDING, null);
        assertTrue(gateway.calls.isEmpty());
        binding.bind(connection.url);                 // the R-71 task ran
        assertTrue(save(carbs(writer.newDraft(), 20, NOW)).ok());
    }
}
