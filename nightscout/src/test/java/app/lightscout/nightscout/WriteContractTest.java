package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.Test;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.WriteAck;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;

/**
 * TST-04 write contracts IF-38…IF-41 (CP-03 §9.3 C4-14…C4-19; 05 §3; CP-00A R-40, R-50).
 */
public class WriteContractTest extends GatewayTestBase {

    private static final String ID = "0b5a5f8e-0000-4000-8000-0000000000aa";
    /** Event time 2030-01-01T00:00:00Z; Europe/Warsaw offset +60 min (fake zone). */
    private static final Instant TIME = Instant.ofEpochMilli(Fakes.T0);

    private static Entry entry(EntryType type, Integer glucose, Integer carbs, Integer insulinCenti) {
        return new Entry(ID, EntryOrigin.TREATMENTS, type, TIME, glucose, carbs, insulinCenti, null, null, null,
                "LightScout", "LightScout", null, true, null);
    }

    private JSONObject postAndCapture(Entry e) throws Exception {
        server.enqueue(json(201, "{\"status\":201,\"identifier\":\"" + ID + "\",\"lastModified\":1893456001000}"));
        assertTrue(gateway.create(e).ok());
        RecordedRequest req = take();
        while (!"POST".equals(req.getMethod())) {
            req = take(); // skip the IF-30 request of the first call
        }
        assertEquals("/api/v3/treatments", req.getUrl().encodedPath());
        assertTrue(req.getHeaders().get("Content-Type").startsWith("application/json"));
        return new JSONObject(req.getBody().utf8());
    }

    private static void assertCommon(JSONObject body) throws Exception {
        assertEquals(ID, body.getString("identifier"));
        assertEquals(Fakes.T0, body.getLong("date"));
        assertEquals("2030-01-01T00:00:00.000Z", body.getString("created_at"));
        assertEquals(Instant.ofEpochMilli(body.getLong("date")), Instant.parse(body.getString("created_at")));
        assertEquals(60, body.getInt("utcOffset"));
        assertEquals("LightScout", body.getString("app"));
        assertEquals("LightScout", body.getString("enteredBy"));
    }

    /** C4-14 (05 §3, ADR-038): common fields + per-type fields for all 5 writable types. */
    @Test
    public void c414_payloadsForAllFiveTypes() throws Exception {
        enqueueJwt();
        JSONObject bg = postAndCapture(entry(EntryType.BG_CHECK, 118, null, null));
        assertCommon(bg);
        assertEquals("BG Check", bg.getString("eventType"));
        assertEquals(118, bg.getInt("glucose"));
        assertEquals("Finger", bg.getString("glucoseType"));
        assertEquals("mg/dl", bg.getString("units"));

        JSONObject carbs = postAndCapture(entry(EntryType.CARBS, null, 15, null));
        assertCommon(carbs);
        assertEquals("Carb Correction", carbs.getString("eventType"));
        assertEquals(15, carbs.getInt("carbs"));
        assertFalse(carbs.has("glucose"));

        JSONObject bolus = postAndCapture(entry(EntryType.BOLUS, null, null, 150));
        assertCommon(bolus);
        assertEquals("Correction Bolus", bolus.getString("eventType"));
        assertEquals(1.5, bolus.getDouble("insulin"), 0.0);
        JSONObject bolus2 = postAndCapture(entry(EntryType.BOLUS, null, null, 123));
        assertEquals("1.23", bolus2.get("insulin").toString());

        JSONObject site = postAndCapture(entry(EntryType.SITE_CHANGE, null, null, null));
        assertCommon(site);
        assertEquals("Site Change", site.getString("eventType"));

        JSONObject sensor = postAndCapture(entry(EntryType.SENSOR_CHANGE, null, null, null));
        assertCommon(sensor);
        assertEquals("Sensor Change", sensor.getString("eventType"));
    }

    @Test
    public void bolusWriteUsesAuthoritativeImportedMilliValue() throws Exception {
        enqueueJwt();
        Entry imported = new Entry(ID, EntryOrigin.TREATMENTS, EntryType.BOLUS, TIME,
                null, null, 237, 2375L, null, null, "note", "LightScout", "LightScout", "Bolus", true, null);
        JSONObject body = postAndCapture(imported);
        assertTrue(body.get("insulin") instanceof Number);
        assertEquals("2.375", body.get("insulin").toString());
    }

    @Test
    public void exactNumericMilliCreateUpdateAndReadBack() throws Exception {
        enqueueJwt();
        long[] milli = {25, 1234, 25000};
        String[] decimal = {"0.025", "1.234", "25"};
        for (int i = 0; i < milli.length; i++) {
            Entry exact = new Entry(ID, EntryOrigin.TREATMENTS, EntryType.BOLUS, TIME,
                    null, null, null, milli[i], null, null, null, "LightScout", "LightScout",
                    "Correction Bolus", true, 1000L);
            JSONObject created = postAndCapture(exact);
            assertTrue(created.get("insulin") instanceof Number);
            assertEquals(decimal[i], created.get("insulin").toString());
            server.enqueue(json(200, "{\"status\":200,\"lastModified\":2000}"));
            assertTrue(gateway.update(exact, 1000L).ok());
            RecordedRequest update = take();
            assertEquals("PUT", update.getMethod());
            assertEquals("/api/v3/treatments/" + ID, update.getUrl().encodedPath());
            JSONObject updated = new JSONObject(update.getBody().utf8());
            assertTrue(updated.get("insulin") instanceof Number);
            assertEquals(decimal[i], updated.get("insulin").toString());
            updated.put("srvModified", 2000L);
            server.enqueue(json(200, "{\"status\":200,\"result\":" + updated + "}"));
            Entry readBack = gateway.get(ID).value();
            assertEquals(Long.valueOf(milli[i]), readBack.insulinMilli());
            assertEquals(ID, readBack.id());
            assertEquals(Long.valueOf(2000L), readBack.srvModifiedMs());
            assertEquals("GET", take().getMethod());
        }
    }

    /** 05 §3: utcOffset is the phone offset at the event time (summer time +120). */
    @Test
    public void utcOffsetAtEventTime() throws Exception {
        enqueueJwt();
        Entry summer = new Entry(ID, EntryOrigin.TREATMENTS, EntryType.CARBS, Instant.parse("2030-07-01T12:00:00Z"),
                null, 10, null, null, null, null, null, null, null, true, null);
        JSONObject body = postAndCapture(summer);
        assertEquals(120, body.getInt("utcOffset"));
        clock.zone = ZoneId.of("UTC");
        assertEquals(0, postAndCapture(summer).getInt("utcOffset"));
    }

    /** 05 §3: PUMP_ALERT / TEMP_BASAL are never written; a missing value is never sent. No request at all. */
    @Test
    public void readOnlyTypesAreNeverWritten() throws Exception {
        assertFailure(gateway.create(entry(EntryType.PUMP_ALERT, null, null, null)),
                FetchOutcome.OK, DetailCode.REJECTED_400_422);
        assertFailure(gateway.create(entry(EntryType.TEMP_BASAL, null, null, null)),
                FetchOutcome.OK, DetailCode.REJECTED_400_422);
        assertFailure(gateway.create(entry(EntryType.BG_CHECK, null, null, null)),
                FetchOutcome.OK, DetailCode.REJECTED_400_422);
        assertFailure(gateway.update(entry(EntryType.TEMP_BASAL, null, null, null), 1),
                FetchOutcome.OK, DetailCode.REJECTED_400_422);
        assertEquals(0, server.getRequestCount());
    }

    /** C4-15 (IF-38, GIR-3): 201 → deduplicated=false with lastModified; 200 + isDeduplication → ok, true. */
    @Test
    public void c415_createAndDeduplication() throws Exception {
        enqueueJwt();
        server.enqueue(json(201, "{\"status\":201,\"identifier\":\"" + ID + "\",\"lastModified\":1893456001000}"));
        Result<WriteAck> created = gateway.create(entry(EntryType.CARBS, null, 15, null));
        assertEquals(new WriteAck(ID, 1_893_456_001_000L, false), created.value());
        server.enqueue(json(200, "{\"status\":200,\"identifier\":\"" + ID
                + "\",\"lastModified\":1893456001000,\"isDeduplication\":true}"));
        Result<WriteAck> dedup = gateway.create(entry(EntryType.CARBS, null, 15, null));
        assertTrue(dedup.ok());
        assertTrue(dedup.value().deduplicated());
        server.enqueue(status(201));
        Result<WriteAck> noBody = gateway.create(entry(EntryType.CARBS, null, 15, null));
        assertEquals(new WriteAck(ID, -1L, false), noBody.value());
    }

    /** C4-16 (IF-38, C-4, R-50): each failure maps to a distinct result; exactly one POST each (no retry). */
    @Test
    public void c416_createFailuresWithoutRetry() throws Exception {
        assertCreateFailure(status(400), FetchOutcome.OK, DetailCode.REJECTED_400_422);
        assertCreateFailure(status(422), FetchOutcome.OK, DetailCode.REJECTED_400_422);
        assertCreateFailure(status(401), FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        assertCreateFailure(status(403), FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        assertCreateFailure(status(500), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
    }

    /** C4-16 / C-3: a write that exceeds its 15 s budget (scaled) → TIMEOUT, one POST. */
    @Test
    public void c416_createTimeout() throws Exception {
        OkHttpNightscoutGateway fast = newGateway(new HttpSettings(Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofMillis(400), 8L * 1024 * 1024, 3, true));
        fast.configure(baseUrl(), tokens);
        enqueueJwt();
        server.enqueue(new MockResponse.Builder().code(201).headersDelay(1500, TimeUnit.MILLISECONDS)
                .body("{}").build());
        assertFailure(fast.create(entry(EntryType.CARBS, null, 15, null)),
                FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        takeJwtRequest();
        assertEquals("POST", take().getMethod());
        assertEquals(2, server.getRequestCount());
    }

    private void assertCreateFailure(MockResponse response, FetchOutcome outcome, String detail) throws Exception {
        int before = server.getRequestCount();
        enqueueJwt();
        server.enqueue(response);
        // a fresh gateway each time so that every case starts with one IF-30
        OkHttpNightscoutGateway g = newGateway(TEST_SETTINGS);
        g.configure(baseUrl(), tokens);
        assertFailure(g.create(entry(EntryType.CARBS, null, 15, null)), outcome, detail);
        assertEquals(before + 2, server.getRequestCount());
        takeJwtRequest();
        assertEquals("POST", take().getMethod());
    }

    /** C4-17 (IF-39): 200 → Entry incl. srvModified (own); 404 / 410 → conflict failure. */
    @Test
    public void c417_getOne() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, "{\"status\":200,\"result\":" + Fixtures.resultArray("FX-10-own-v3.json")
                .getJSONObject(0).toString() + "}"));
        Result<Entry> r = gateway.get("0b5a5f8e-0000-4000-8000-000000000010");
        assertTrue(r.ok());
        assertEquals(Long.valueOf(1_893_456_001_234L), r.value().srvModifiedMs());
        assertTrue(r.value().own());
        takeJwtRequest();
        RecordedRequest req = take();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/v3/treatments/0b5a5f8e-0000-4000-8000-000000000010", req.getUrl().encodedPath());
        server.enqueue(status(404));
        assertFailure(gateway.get("x"), FetchOutcome.OK, DetailCode.GONE_404_410);
        server.enqueue(status(410));
        assertFailure(gateway.get("x"), FetchOutcome.OK, DetailCode.GONE_404_410);
    }

    /** C4-18 (IF-40, R-40): If-Unmodified-Since rounded up; 200 → ok; 412 / 410 / 404 → conflict failures. */
    @Test
    public void c418_updateWithPrecondition() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, "{\"status\":200,\"lastModified\":1893456009000}"));
        Result<WriteAck> ok = gateway.update(entry(EntryType.CARBS, null, 20, null), 1_700_000_000_123L);
        assertEquals(new WriteAck(ID, 1_893_456_009_000L, false), ok.value());
        takeJwtRequest();
        RecordedRequest req = take();
        assertEquals("PUT", req.getMethod());
        assertEquals("/api/v3/treatments/" + ID, req.getUrl().encodedPath());
        assertEquals("Tue, 14 Nov 2023 22:13:21 GMT", req.getHeaders().get("If-Unmodified-Since"));
        JSONObject body = new JSONObject(req.getBody().utf8());
        assertEquals(ID, body.getString("identifier"));
        assertEquals("LightScout", body.getString("app"));
        assertEquals(20, body.getInt("carbs"));

        server.enqueue(status(412));
        assertFailure(gateway.update(entry(EntryType.CARBS, null, 20, null), 1), FetchOutcome.OK,
                DetailCode.PRECONDITION_412);
        server.enqueue(status(410));
        assertFailure(gateway.update(entry(EntryType.CARBS, null, 20, null), 1), FetchOutcome.OK,
                DetailCode.GONE_404_410);
        server.enqueue(status(404));
        assertFailure(gateway.update(entry(EntryType.CARBS, null, 20, null), 1), FetchOutcome.OK,
                DetailCode.GONE_404_410);
        server.enqueue(status(422));
        assertFailure(gateway.update(entry(EntryType.CARBS, null, 20, null), 1), FetchOutcome.OK,
                DetailCode.REJECTED_400_422);
        server.enqueue(status(401));
        assertFailure(gateway.update(entry(EntryType.CARBS, null, 20, null), 1), FetchOutcome.TOKEN_REJECTED,
                DetailCode.AUTH_401);
        assertEquals(7, server.getRequestCount()); // no automatic retry of writes
    }

    /** IR-32 (Q-34, SR-03): a write 401 is not repeated but discards the JWT; the next call fetches IF-30 again. */
    @Test
    public void write401DiscardsJwt() throws Exception {
        enqueueJwt();
        server.enqueue(status(401));
        assertFailure(gateway.create(entry(EntryType.CARBS, null, 15, null)), FetchOutcome.TOKEN_REJECTED,
                DetailCode.AUTH_401);
        assertEquals(2, server.getRequestCount());
        String jwt2 = enqueueJwt();
        server.enqueue(json(200, Fixtures.text("FX-24-last-modified.json")));
        assertTrue(gateway.lastModified().ok());
        takeJwtRequest();
        assertEquals("POST", take().getMethod());
        takeJwtRequest();
        assertEquals("Bearer " + jwt2, take().getHeaders().get("Authorization"));
        assertEquals(4, server.getRequestCount());
    }

    /** R-40: 200 without lastModified → one IF-39 read for srvModified; if that read fails → -1. */
    @Test
    public void updateWithoutLastModifiedReadsOnce() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, "{\"status\":200}"));
        server.enqueue(json(200, "{\"status\":200,\"result\":{\"identifier\":\"" + ID + "\",\"eventType\":"
                + "\"Carb Correction\",\"carbs\":20,\"created_at\":\"2030-01-01T00:00:00.000Z\",\"app\":\"LightScout\","
                + "\"srvModified\":1893456005555}}"));
        assertEquals(1_893_456_005_555L,
                gateway.update(entry(EntryType.CARBS, null, 20, null), 1).value().srvModifiedMs());
        server.enqueue(status(204));
        server.enqueue(status(500));
        Result<WriteAck> unknown = gateway.update(entry(EntryType.CARBS, null, 20, null), 1);
        assertTrue(unknown.ok());
        assertEquals(-1L, unknown.value().srvModifiedMs());
        takeJwtRequest();
        assertEquals("PUT", take().getMethod());
        assertEquals("GET", take().getMethod());
    }

    /** C4-19 (IF-41, R-50): 204 and 200 → ok (srvModified 0); 404 / 410 → GONE_404_410 („already deleted”). */
    @Test
    public void c419_delete() throws Exception {
        enqueueJwt();
        server.enqueue(status(204));
        assertEquals(new WriteAck(ID, 0L, false), gateway.delete(ID).value());
        takeJwtRequest();
        RecordedRequest req = take();
        assertEquals("DELETE", req.getMethod());
        assertEquals("/api/v3/treatments/" + ID, req.getUrl().encodedPath());
        server.enqueue(json(200, "{\"status\":200}"));
        assertTrue(gateway.delete(ID).ok());
        server.enqueue(status(404));
        assertFailure(gateway.delete(ID), FetchOutcome.OK, DetailCode.GONE_404_410);
        server.enqueue(status(410));
        assertFailure(gateway.delete(ID), FetchOutcome.OK, DetailCode.GONE_404_410);
        server.enqueue(status(503));
        assertFailure(gateway.delete(ID), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
    }
}
