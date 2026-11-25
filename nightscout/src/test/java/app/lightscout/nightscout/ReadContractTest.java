package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;

/**
 * TST-04 read contracts IF-31…IF-37 (CP-03 §9.3 C4-01, C4-08…C4-13, C4-22).
 */
public class ReadContractTest extends GatewayTestBase {

    private static final String SGV = "{\"identifier\":\"r%d\",\"type\":\"sgv\",\"sgv\":%d,\"date\":%d,"
            + "\"direction\":\"Flat\",\"device\":\"share2\"}";

    private static String sgv(int i) {
        return String.format(java.util.Locale.ROOT, SGV, i, 100 + i, Fakes.T0 + i * 300_000L);
    }

    // ------------------------------------------------------------------------------------------- C4-01 IF-31

    /** C4-01: no Authorization, no token read; 15.0.x + 3.x → raw ServerInfo. */
    @Test
    public void c401_versionSendsNoTokenAndReturnsRawInfo() throws Exception {
        server.enqueue(json(200, Fixtures.text("FX-22-version.json")));
        Result<ServerInfo> r = gateway.version(baseUrl());
        assertTrue(r.ok());
        assertEquals(new ServerInfo("15.0.6", "3.0.3-alpha"), r.value());
        RecordedRequest req = take();
        assertEquals("/api/v3/version", req.getUrl().encodedPath());
        assertNull(req.getHeaders().get("Authorization"));
        assertEquals(0, tokens.calls.get());
        assertEquals(1, server.getRequestCount());
    }

    /** C4-01 + CP-00A R-50: the gateway returns 14.x raw (SERVER_TOO_OLD is applied by the use case). */
    @Test
    public void c401_oldVersionIsReturnedRaw() throws Exception {
        server.enqueue(json(200, "{\"status\":200,\"result\":{\"version\":\"14.2.6\",\"apiVersion\":\"3.0.3-alpha\"}}"));
        Result<ServerInfo> r = gateway.version(baseUrl());
        assertTrue(r.ok());
        assertEquals("14.2.6", r.value().version());
    }

    /** C4-01 + R-50: missing apiVersion → raw null (NOT_API_V3 is applied by the use case). */
    @Test
    public void c401_missingApiVersionIsReturnedRaw() throws Exception {
        server.enqueue(json(200, "{\"status\":200,\"result\":{\"version\":\"15.0.6\"}}"));
        Result<ServerInfo> r = gateway.version(baseUrl());
        assertTrue(r.ok());
        assertNull(r.value().apiVersion());
    }

    /** C4-01: missing version, HTML page, 404 → NOT_NIGHTSCOUT / NO_VERSION; 5xx → NS_NOT_RESPONDING. */
    @Test
    public void c401_notNightscout() throws Exception {
        server.enqueue(json(200, "{\"status\":200,\"result\":{\"apiVersion\":\"3.0.3-alpha\"}}"));
        assertFailure(gateway.version(baseUrl()), FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        server.enqueue(new mockwebserver3.MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/html").body("<html><body>Some other site</body></html>").build());
        assertFailure(gateway.version(baseUrl()), FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        server.enqueue(status(404));
        assertFailure(gateway.version(baseUrl()), FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        server.enqueue(json(200, "{\"status\":200,\"result\":{\"version\":15}}"));
        assertFailure(gateway.version(baseUrl()), FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        server.enqueue(status(502));
        assertFailure(gateway.version(baseUrl()), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        assertEquals(0, tokens.calls.get());
    }

    /** IF-31 with an unusable URL: no request, not responding. */
    @Test
    public void versionWithBadUrl() throws Exception {
        assertFailure(gateway.version("not a url"), FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
        assertFailure(gateway.version(null), FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
        assertEquals(0, server.getRequestCount());
    }

    /** IF-31 under a base path: the path is kept. */
    @Test
    public void versionKeepsBasePath() throws Exception {
        server.enqueue(json(200, Fixtures.text("FX-22-version.json")));
        assertTrue(gateway.version(server.url("/ns/").toString() + "?x=1").ok());
        assertEquals("/ns/api/v3/version", take().getUrl().encodedPath());
    }

    // ------------------------------------------------------------------------------------------- C4-08, C4-09

    /**
     * C4-08 (BUG-15, D-060): IF-32 letters per collection derived from the IF-30 {@code permissionGroups}:
     * {@code r} (readable), {@code crud} (readable + treatments CUD), {@code cr} (readable + careportal).
     * Each call is exactly one fresh IF-30 request; {@code /api/v3/status} is never read.
     */
    @Test
    public void c408_permissions() throws Exception {
        enqueueJwtWithGroups(Fixtures.READABLE_GROUPS);
        Result<Permissions> r = gateway.permissions();
        assertTrue(r.ok());
        assertEquals("r", r.value().letters().get(NsCollection.TREATMENTS));
        assertEquals(4, r.value().letters().size());
        takeJwtRequest();
        assertEquals(1, server.getRequestCount());

        enqueueJwtWithGroups(Fixtures.READABLE_PLUS_TREATMENTS_CUD_GROUPS);
        Permissions crud = gateway.permissions().value();
        assertEquals("crud", crud.letters().get(NsCollection.TREATMENTS));
        assertEquals("r", crud.letters().get(NsCollection.ENTRIES));
        enqueueJwtWithGroups(Fixtures.READABLE_PLUS_CAREPORTAL_GROUPS);
        Permissions cr = gateway.permissions().value();
        assertEquals("cr", cr.letters().get(NsCollection.TREATMENTS));
        assertFalse(cr.has(NsCollection.TREATMENTS, 'u'));
        takeJwtRequest();
        takeJwtRequest();
        assertEquals(3, server.getRequestCount());
    }

    /**
     * BUG-15 regression: S-06 check 5 / check 6 rule (ServerPolicy: entries+treatments {@code r}; treatments
     * {@code c}, {@code u} and {@code d}) on the real production groups passes, although the same server's buggy
     * {@code /status} reports {@code treatments:"r"} for this subject (FX-23, kept as evidence of that report,
     * EV-11; it is never served here). The JWT fetched by IF-32 is then reused by the next data call.
     */
    @Test
    public void bug15_check6PassesForLeastPrivilegeCudToken() throws Exception {
        // the evidence: the buggy v3 report for the same subject says treatments "r" only
        assertEquals("r", Json.obj(Json.resultObject(Fixtures.text("FX-23-status.json")), "apiPermissions")
                .getString("treatments"));
        enqueueJwtWithGroups(Fixtures.READABLE_PLUS_TREATMENTS_CUD_GROUPS);
        Permissions p = gateway.permissions().value();
        assertTrue(check5(p));
        assertTrue(check6(p));
        takeJwtRequest();
        assertEquals(1, server.getRequestCount());

        server.enqueue(json(200, Fixtures.text("FX-24-last-modified.json")));
        assertTrue(gateway.lastModified().ok());
        RecordedRequest next = take();
        assertEquals("/api/v3/lastModified", next.getUrl().encodedPath());
        assertTrue(next.getHeaders().get("Authorization").startsWith("Bearer "));
        assertEquals(2, server.getRequestCount());
    }

    /** BUG-15: check 6 fails for {@code careportal} (create only) and for a read-only token; check 5 passes. */
    @Test
    public void bug15_check6FailsForCareportalAndReadOnly() throws Exception {
        enqueueJwtWithGroups(Fixtures.READABLE_PLUS_CAREPORTAL_GROUPS);
        Permissions careportal = gateway.permissions().value();
        assertTrue(check5(careportal));
        assertFalse(check6(careportal));
        enqueueJwtWithGroups(Fixtures.READABLE_GROUPS);
        Permissions readOnly = gateway.permissions().value();
        assertTrue(check5(readOnly));
        assertFalse(check6(readOnly));
        enqueueJwtWithGroups("[[\"api:treatments:create,update,delete\"],[]]");
        Permissions writeOnly = gateway.permissions().value();
        assertFalse(check5(writeOnly));
        assertTrue(check6(writeOnly));
    }

    /**
     * BUG-15 / Q-97: missing or malformed {@code permissionGroups} → NON_JSON (same class as a malformed body) for
     * IF-32 only; the JWT from that IF-30 stays usable, so the next data call needs no second IF-30.
     */
    @Test
    public void bug15_missingOrMalformedGroups() throws Exception {
        String lastModified = Fixtures.text("FX-24-last-modified.json");
        int expected = 0;
        for (String groups : new String[] {null, "\"*\"", "[\"*\"]", "[[1]]", "[[null]]", "null", "{}"}) {
            String jwt = enqueueJwtWithGroups(groups);
            assertFailure(gateway.permissions(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
            takeJwtRequest();
            server.enqueue(json(200, lastModified));
            assertTrue(gateway.lastModified().ok());
            RecordedRequest data = take();
            assertEquals("/api/v3/lastModified", data.getUrl().encodedPath());
            assertEquals("Bearer " + jwt, data.getHeaders().get("Authorization"));
            expected += 2;
            assertEquals(expected, server.getRequestCount());
        }
        assertTrue(List.copyOf(logger.lines).stream().noneMatch(l -> l.contains("api:") || l.contains("*")));
    }

    /** BUG-15: IF-30 failures on IF-32 map like any JWT fetch (401, 403, 5xx). */
    @Test
    public void bug15_jwtFailures() throws Exception {
        server.enqueue(status(401));
        assertFailure(gateway.permissions(), FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        server.enqueue(status(403));
        assertFailure(gateway.permissions(), FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        server.enqueue(status(503));
        assertFailure(gateway.permissions(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        assertEquals(3, server.getRequestCount());
    }

    /** S-06 check 5 rule (ServerPolicy.hasRead, CP-02). */
    private static boolean check5(Permissions p) {
        return p.has(NsCollection.ENTRIES, 'r') && p.has(NsCollection.TREATMENTS, 'r');
    }

    /** S-06 check 6 rule (ServerPolicy.hasWrite, CP-02, D-018). */
    private static boolean check6(Permissions p) {
        return p.has(NsCollection.TREATMENTS, 'c') && p.has(NsCollection.TREATMENTS, 'u')
                && p.has(NsCollection.TREATMENTS, 'd');
    }

    /** C4-09 / M-14: IF-33 → 4 keys; a value in the future is returned unchanged. */
    @Test
    public void c409_lastModified() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.text("FX-24-last-modified.json")));
        Result<Map<NsCollection, Long>> r = gateway.lastModified();
        assertTrue(r.ok());
        assertEquals(4, r.value().size());
        assertEquals(Long.valueOf(1_893_455_900_000L), r.value().get(NsCollection.ENTRIES));
        assertEquals(Long.valueOf(1_893_460_000_000L), r.value().get(NsCollection.TREATMENTS)); // > now + 10 min
        takeJwtRequest();
        assertEquals("/api/v3/lastModified", take().getUrl().encodedPath());
    }

    // ------------------------------------------------------------------------------------------- C4-10, C4-11

    /** C4-10 (IF-34, R-23 type filter, C-8): query and complete flag. */
    @Test
    public void c410_readingsSinceQueryAndCompleteness() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.envelope(sgv(1), sgv(2), sgv(3))));
        Result<Page<Reading>> full = gateway.readingsSince(Fakes.T0 - 600_000L, 3);
        assertTrue(full.ok());
        assertEquals(3, full.value().items().size());
        assertFalse("limit reached → incomplete", full.value().complete());
        takeJwtRequest();
        HttpUrl url = take().getUrl();
        assertEquals("/api/v3/entries", url.encodedPath());
        assertEquals("sgv", url.queryParameter("type$eq"));
        assertEquals(Long.toString(Fakes.T0 - 600_000L), url.queryParameter("date$gt"));
        assertEquals("date", url.queryParameter("sort"));
        assertEquals("3", url.queryParameter("limit"));
        assertNull(url.queryParameter("skip"));
        assertTrue(url.encodedQuery().contains("date$gt="));

        server.enqueue(json(200, Fixtures.envelope(sgv(1), sgv(2))));
        assertTrue(gateway.readingsSince(0, 3).value().complete());
        take();
    }

    /** C-8: completeness counts raw documents, not mapped ones (a skipped doc never makes a full page complete). */
    @Test
    public void completenessCountsRawDocuments() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.envelope(sgv(1), "{\"identifier\":\"bad\",\"type\":\"sgv\"}")));
        Result<Page<Reading>> r = gateway.readingsSince(0, 2);
        assertEquals(1, r.value().items().size());
        assertFalse(r.value().complete());
        assertTrue(logger.contains(EventCode.MAPPING_SKIPPED.name() + ":ENTRIES:1"));
    }

    /** C4-10: mbg, bounded windows (R-23) and limit clamp ≤ 1000 (C-7). */
    @Test
    public void c410_meterBgAndWindows() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.text("FX-04-mbg.json")));
        Result<Page<Entry>> mbg = gateway.meterBgSince(5, 5000);
        assertEquals(1, mbg.value().items().size());
        assertTrue(mbg.value().complete());
        takeJwtRequest();
        HttpUrl u1 = take().getUrl();
        assertEquals("mbg", u1.queryParameter("type$eq"));
        assertEquals("1000", u1.queryParameter("limit"));

        Instant from = Instant.ofEpochMilli(Fakes.T0);
        Instant to = from.plusSeconds(86_400);
        server.enqueue(json(200, Fixtures.envelope(sgv(1))));
        assertTrue(gateway.readingsWindow(from, to, 1000).ok());
        HttpUrl u2 = take().getUrl();
        assertEquals("sgv", u2.queryParameter("type$eq"));
        assertEquals(Long.toString(Fakes.T0), u2.queryParameter("date$gte"));
        assertEquals(Long.toString(Fakes.T0 + 86_400_000L), u2.queryParameter("date$lt"));

        server.enqueue(json(200, Fixtures.text("FX-04-mbg.json")));
        assertTrue(gateway.meterBgWindow(from, to, 1000).ok());
        assertEquals("mbg", take().getUrl().queryParameter("type$eq"));
    }

    /** C4-11 (IF-35): ISO 8601 UTC window, sort, limit; complete flag. */
    @Test
    public void c411_treatmentsWindow() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.text("FX-12-known-types.json")));
        Instant from = Instant.ofEpochMilli(Fakes.T0);
        Result<Page<Entry>> r = gateway.entriesWindow(from, from.plusSeconds(48 * 3600), 4);
        assertEquals(4, r.value().items().size());
        assertFalse(r.value().complete());
        takeJwtRequest();
        HttpUrl url = take().getUrl();
        assertEquals("/api/v3/treatments", url.encodedPath());
        assertEquals("2030-01-01T00:00:00.000Z", url.queryParameter("created_at$gte"));
        assertEquals("2030-01-03T00:00:00.000Z", url.queryParameter("created_at$lt"));
        assertEquals("created_at", url.queryParameter("sort"));
        assertEquals("4", url.queryParameter("limit"));

        server.enqueue(json(200, Fixtures.text("FX-12-known-types.json")));
        assertTrue(gateway.entriesWindow(from, from.plusSeconds(3600), 5).value().complete());
    }

    // ------------------------------------------------------------------------------------------- C4-12, C4-13

    /** C4-12 (IF-36): devicestatus and profile queries. */
    @Test
    public void c412_deviceStatusAndProfile() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.text("FX-20-devicestatus.json")));
        Result<List<DeviceStatus>> ds = gateway.deviceStatusSince(Instant.ofEpochMilli(Fakes.T0));
        assertEquals(6, ds.value().size());
        takeJwtRequest();
        HttpUrl u1 = take().getUrl();
        assertEquals("/api/v3/devicestatus", u1.encodedPath());
        assertEquals("2030-01-01T00:00:00.000Z", u1.queryParameter("created_at$gte"));
        assertEquals("created_at", u1.queryParameter("sort$desc"));
        assertEquals("300", u1.queryParameter("limit"));

        server.enqueue(json(200, Fixtures.text("FX-21-profile.json")));
        Result<Profile> p = gateway.latestProfile();
        assertEquals("fx21-0001", p.value().id());
        HttpUrl u2 = take().getUrl();
        assertEquals("/api/v3/profile", u2.encodedPath());
        assertEquals("created_at", u2.queryParameter("sort$desc"));
        assertEquals("1", u2.queryParameter("limit"));

        server.enqueue(json(200, "{\"status\":200,\"result\":[]}"));
        Result<Profile> none = gateway.latestProfile();
        assertTrue(none.ok());
        assertNull(none.value());
    }

    /** C4-13 (IF-37): history path; isValid:false → deletion; empty array (FX-26) → empty complete page. */
    @Test
    public void c413_history() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.text("FX-26-history.json")));
        Result<Page<Change>> r = gateway.history(NsCollection.ENTRIES, 1_893_455_999_999L, 500);
        assertTrue(r.ok());
        assertEquals(3, r.value().items().size());
        assertTrue(r.value().items().get(1).deleted());
        takeJwtRequest();
        HttpUrl url = take().getUrl();
        assertEquals("/api/v3/entries/history/1893455999999", url.encodedPath());
        assertEquals("500", url.queryParameter("limit"));

        server.enqueue(json(200, "{\"status\":200,\"result\":[]}"));
        Result<Page<Change>> empty = gateway.history(NsCollection.TREATMENTS, 7, 500);
        assertTrue(empty.value().items().isEmpty());
        assertTrue(empty.value().complete());
        assertEquals("/api/v3/treatments/history/7", take().getUrl().encodedPath());
    }

    // ------------------------------------------------------------------------------------------- C4-22

    /** C4-22 (C-5): 500, 503 and a non-JSON 200 on a data call → NS_NOT_RESPONDING. */
    @Test
    public void c422_serverErrorsAndNonJson() throws Exception {
        enqueueJwt();
        server.enqueue(status(500));
        assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        server.enqueue(status(503));
        assertFailure(gateway.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        server.enqueue(json(200, "<html>proxy error page</html>"));
        assertFailure(gateway.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
        server.enqueue(json(200, "{\"status\":200,\"result\":{}}"));
        assertFailure(gateway.entriesWindow(Instant.EPOCH, Instant.ofEpochMilli(1), 10),
                FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
        server.enqueue(status(404));
        // IF-32 is answered by its own fresh IF-30 (BUG-15): a 404 there is classified the same way
        assertFailure(gateway.permissions(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
        assertEquals(6, server.getRequestCount()); // one JWT, four data calls, one IF-32 IF-30: no retries (C-4)
    }
}
