package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.junit.Test;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okio.Buffer;

/**
 * TST-04 transport contracts (CP-03 §9.3 C4-20…C4-24, C4-26; C-1, C-3, C-10, C-11, SR-15…SR-18) and the JVM
 * check behind VR-19 ({@link #c423_vr19_redirectPolicy()}).
 */
public class TransportContractTest extends GatewayTestBase {

    private static final String LAST_MODIFIED = Fixtures.text("FX-24-last-modified.json");

    // ------------------------------------------------------------------------------------------- client config

    /** C-3, C-10, C-11, SR-15/16, C-4: the production OkHttp configuration. */
    @Test
    public void productionClientConfiguration() throws Exception {
        OkHttpClient c = NsHttp.buildClient(HttpSettings.PRODUCTION);
        assertEquals(10_000, c.connectTimeoutMillis());
        assertEquals(15_000, c.readTimeoutMillis());
        assertEquals(20_000, c.callTimeoutMillis());
        assertFalse(c.followRedirects());
        assertFalse(c.followSslRedirects());
        assertFalse(c.retryOnConnectionFailure());
        assertEquals(List.of(ConnectionSpec.MODERN_TLS), c.connectionSpecs());
        assertTrue("no pinning", c.certificatePinner().getPins().isEmpty());
        assertEquals("no logging / other interceptors", 1, c.interceptors().size());
        assertTrue(c.interceptors().get(0) instanceof NsHttp.AuthInterceptor);
        assertTrue(c.networkInterceptors().isEmpty());
        NsHttp http = new NsHttp(HttpSettings.PRODUCTION, connectivity, logger);
        assertEquals(15_000, http.client(true).callTimeoutMillis());
        assertEquals(20_000, http.client(false).callTimeoutMillis());
    }

    /** C-1: the production client refuses cleartext; no request reaches the server. */
    @Test
    public void c1_productionClientRefusesCleartext() throws Exception {
        OkHttpNightscoutGateway prod = newGateway(HttpSettings.PRODUCTION);
        assertFailure(prod.version(baseUrl()), FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
        prod.configure(baseUrl(), tokens);
        assertFailure(prod.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
        assertEquals(0, server.getRequestCount());
    }

    /** C-5 TLS: an https call to a plain-http peer fails the handshake → NS_NOT_RESPONDING / TLS. */
    @Test
    public void c5_tlsFailure() throws Exception {
        // BUG-01: a deterministic non-TLS peer on one loopback address (mockwebserver sometimes kept reading the
        // ClientHello as an HTTP request, so the handshake ended in a 15 s read timeout instead of a TLS error).
        try (NonTlsPeer peer = new NonTlsPeer()) {
            OkHttpNightscoutGateway prod = newGateway(HttpSettings.PRODUCTION);
            for (int i = 0; i < 20; i++) {
                assertFailure(prod.version(peer.httpsUrl()), FetchOutcome.NS_NOT_RESPONDING, DetailCode.TLS);
            }
            prod.configure(peer.httpsUrl(), tokens);
            assertFailure(prod.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.TLS);
            connectivity.online = false;
            assertFailure(prod.version(peer.httpsUrl()), FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        }
    }

    // ------------------------------------------------------------------------------------------- C4-20 timeouts

    /** C4-20 (C-3): headers delayed beyond the read budget → TIMEOUT; reads and writes have separate budgets. */
    @Test
    public void c420_timeouts() throws Exception {
        OkHttpNightscoutGateway fast = newGateway(new HttpSettings(Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofMillis(1500), Duration.ofMillis(500), 8L * 1024 * 1024, 3, true));
        fast.configure(baseUrl(), tokens);
        enqueueJwt();
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(2500, TimeUnit.MILLISECONDS)
                .body(LAST_MODIFIED).build());
        assertFailure(fast.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);

        // 900 ms: inside the read budget (1.5 s), beyond the write budget (0.5 s)
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(900, TimeUnit.MILLISECONDS)
                .body(LAST_MODIFIED).build());
        assertTrue(fast.lastModified().ok());
        server.enqueue(new MockResponse.Builder().code(204).headersDelay(900, TimeUnit.MILLISECONDS).build());
        assertFailure(fast.delete("id-1"), FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
    }

    // ------------------------------------------------------------------------------------------- C4-21

    /** C4-21 (C-5): connection refused → NO_INTERNET without a validated network, NS_NOT_RESPONDING with one. */
    @Test
    public void c421_connectionRefused() throws Exception {
        MockWebServer closed = new MockWebServer();
        closed.start();
        String deadUrl = closed.url("/").toString();
        closed.close();
        gateway.configure(deadUrl, tokens);
        connectivity.online = false;
        assertFailure(gateway.lastModified(), FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        assertFailure(gateway.version(deadUrl), FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        connectivity.online = true;
        assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
        assertFailure(gateway.version(deadUrl), FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
    }

    // ------------------------------------------------------------------------------------------- C4-23 / VR-19

    /**
     * C4-23 / VR-19 (C-10, SR-17, CP-00A R-40): cross-origin 302 not followed (the other host receives nothing),
     * {@code REDIRECT_REFUSED} logged; same-origin 302 followed with {@code Authorization}; more than 3 hops
     * refused; writes follow only 307/308. The https→http case is covered on {@code HttpUrl} pairs in
     * {@link SupportRulesTest#redirectOriginRule()} (no {@code okhttp-tls} — R-40).
     */
    @Test
    public void c423_vr19_redirectPolicy() throws Exception {
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            enqueueJwt();
            // cross-origin (other port) redirect on a JWT call
            server.enqueue(new MockResponse.Builder().code(302)
                    .addHeader("Location", other.url("/api/v3/lastModified").toString()).build());
            assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
            assertEquals(0, other.getRequestCount());
            assertTrue(logger.contains(EventCode.REDIRECT_REFUSED.name()));

            // cross-host via the other host name of the same machine
            server.enqueue(new MockResponse.Builder().code(301)
                    .addHeader("Location", "http://127.0.0.1:" + other.getPort() + "/x").build());
            assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
            assertEquals(0, other.getRequestCount());

            // IF-31 (no token) behaves the same (S-06 maps REDIRECT_REFUSED to „doesn't look like Nightscout”)
            server.enqueue(new MockResponse.Builder().code(302)
                    .addHeader("Location", other.url("/api/v3/version").toString()).build());
            assertFailure(gateway.version(baseUrl()), FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
            assertEquals(0, other.getRequestCount());
        }
        takeJwtRequest();
        take();
        take();
        take();

        // same-origin redirect: followed, Authorization kept
        server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/moved/lastModified").build());
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        assertTrue(take().getHeaders().get("Authorization").startsWith("Bearer "));
        RecordedRequest followed = take();
        assertEquals("/moved/lastModified", followed.getUrl().encodedPath());
        assertTrue(followed.getHeaders().get("Authorization").startsWith("Bearer "));

        // more than 3 hops → refused
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/hop" + i).build());
        }
        assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
        for (int i = 0; i < 4; i++) {
            take();
        }

        // a write is not turned into a GET by 302; 307 keeps method and body
        Entry carbs = new Entry("w-1", EntryOrigin.TREATMENTS, EntryType.CARBS, Instant.ofEpochMilli(Fakes.T0),
                null, 10, null, null, null, null, null, null, null, true, null);
        server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/api/v3/treatments2").build());
        assertFailure(gateway.create(carbs), FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
        take();
        server.enqueue(new MockResponse.Builder().code(307).addHeader("Location", "/api/v3/treatments2").build());
        server.enqueue(json(201, "{\"status\":201,\"identifier\":\"w-1\",\"lastModified\":5}"));
        assertTrue(gateway.create(carbs).ok());
        take();
        RecordedRequest repost = take();
        assertEquals("POST", repost.getMethod());
        assertTrue(repost.getBody().utf8().contains("\"identifier\":\"w-1\""));

        // 304 / 300 are never followed
        server.enqueue(status(304));
        assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
    }

    /** C-10 defence in depth: the interceptor never sends Authorization to a host other than the call's origin. */
    @Test
    public void c10_authorizationNeverSentToAnotherOrigin() throws Exception {
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            other.enqueue(json(200, "{}"));
            NsHttp http = new NsHttp(TEST_SETTINGS, connectivity, logger);
            HttpCall call = new HttpCall(Endpoint.IF33_LAST_MODIFIED, "GET", other.url("/x"), null, null, false, "jwt-value",
                    Origin.of(server.url("/")));
            assertEquals(200, http.execute(call).code());
            assertNull(other.takeRequest().getHeaders().get("Authorization"));
        }
    }

    // ------------------------------------------------------------------------------------------- C4-24

    /** C4-24 (C-11, SR-18): body > 8 MB → NS_NOT_RESPONDING / RESPONSE_TOO_LARGE, logged. */
    @Test
    public void c424_oversizedBody() throws Exception {
        enqueueJwt();
        Buffer big = new Buffer();
        big.writeUtf8("{\"status\":200,\"result\":[");
        byte[] filler = new byte[64 * 1024];
        java.util.Arrays.fill(filler, (byte) ' ');
        for (int i = 0; i < 9 * 16; i++) {
            big.write(filler);
        }
        big.writeUtf8("]}");
        server.enqueue(new MockResponse.Builder().code(200).body(big).build());
        assertFailure(gateway.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.RESPONSE_TOO_LARGE);
        assertTrue(logger.contains(EventCode.RESPONSE_TOO_LARGE.name()));
    }

    /** IR-33 (C-11 edge): a decoded body of exactly 8 MB is accepted; 8 MB + 1 byte → RESPONSE_TOO_LARGE. */
    @Test
    public void c424_capEdge() throws Exception {
        enqueueJwt();
        server.enqueue(new MockResponse.Builder().code(200).body(bodyOfSize(8L * 1024 * 1024)).build());
        Result<?> exact = gateway.readingsSince(0, 10);
        assertTrue(String.valueOf(exact), exact.ok());
        server.enqueue(new MockResponse.Builder().code(200).body(bodyOfSize(8L * 1024 * 1024 + 1)).build());
        assertFailure(gateway.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.RESPONSE_TOO_LARGE);
        // chunked (no Content-Length): the streaming check decides
        server.enqueue(new MockResponse.Builder().code(200).chunkedBody(bodyOfSize(8L * 1024 * 1024), 65_536)
                .build());
        assertTrue(gateway.readingsSince(0, 10).ok());
        server.enqueue(new MockResponse.Builder().code(200).chunkedBody(bodyOfSize(8L * 1024 * 1024 + 1), 65_536)
                .build());
        assertFailure(gateway.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.RESPONSE_TOO_LARGE);
    }

    /** A valid empty v3 search envelope padded with spaces to exactly {@code size} bytes. */
    private static Buffer bodyOfSize(long size) {
        String head = "{\"status\":200,\"result\":[";
        String tail = "]}";
        Buffer b = new Buffer().writeUtf8(head);
        long pad = size - head.length() - tail.length();
        byte[] spaces = new byte[64 * 1024];
        java.util.Arrays.fill(spaces, (byte) ' ');
        while (pad > 0) {
            int n = (int) Math.min(pad, spaces.length);
            b.write(spaces, 0, n);
            pad -= n;
        }
        b.writeUtf8(tail);
        assertEquals(size, b.size());
        return b;
    }

    /** IR-29 (SR-29): deeply nested JSON inside the cap → NS_NOT_RESPONDING / NON_JSON, no crash. */
    @Test
    public void deeplyNestedJson() throws Exception {
        enqueueJwt();
        StringBuilder nested = new StringBuilder("{\"status\":200,\"result\":");
        nested.append("[".repeat(100_000)).append("]".repeat(100_000)).append('}');
        server.enqueue(json(200, nested.toString()));
        assertFailure(gateway.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
        server.enqueue(json(200, "[".repeat(100_000)));
        assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
        server.enqueue(json(200, "{\"status\":200,\"result\":{\"version\":" + "[".repeat(100_000) + "}}"));
        assertFailure(gateway.version(baseUrl()), FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
    }

    /** IR-31 (C-3): one whole-call deadline across redirect hops; each hop only gets the remaining budget. */
    @Test
    public void c420_deadlineSpansRedirectHops() throws Exception {
        OkHttpNightscoutGateway fast = newGateway(new HttpSettings(Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofMillis(1500), Duration.ofMillis(1500), 8L * 1024 * 1024, 3, true));
        server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/a")
                .headersDelay(600, TimeUnit.MILLISECONDS).build());
        server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/b")
                .headersDelay(600, TimeUnit.MILLISECONDS).build());
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(600, TimeUnit.MILLISECONDS)
                .body(Fixtures.text("FX-22-version.json")).build());
        long start = System.nanoTime();
        assertFailure(fast.version(baseUrl()), FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("elapsed " + elapsedMs, elapsedMs < 2500); // bounded by the 1.5 s budget, not 3 × 1.5 s

        // the same chain within the budget succeeds
        server.enqueue(new MockResponse.Builder().code(302).addHeader("Location", "/c")
                .headersDelay(200, TimeUnit.MILLISECONDS).build());
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(200, TimeUnit.MILLISECONDS)
                .body(Fixtures.text("FX-22-version.json")).build());
        assertTrue(fast.version(baseUrl()).ok());
    }

    /** C4-24: gzip bomb (small compressed, > 8 MB decompressed) → RESPONSE_TOO_LARGE; read stops at the cap. */
    @Test
    public void c424_gzipBomb() throws Exception {
        enqueueJwt();
        Buffer compressed = new Buffer();
        try (GZIPOutputStream gz = new GZIPOutputStream(compressed.outputStream())) {
            gz.write("{\"status\":200,\"result\":[".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] zeros = new byte[1024 * 1024];
            java.util.Arrays.fill(zeros, (byte) ' ');
            for (int i = 0; i < 20; i++) {
                gz.write(zeros);
            }
            gz.write("]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue("small compressed body", compressed.size() < 200_000);
        server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Encoding", "gzip")
                .body(compressed).build());
        assertFailure(gateway.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.RESPONSE_TOO_LARGE);
        takeJwtRequest();
        RecordedRequest req = take();
        assertEquals("gzip", req.getHeaders().get("Accept-Encoding")); // C-7 transparent gzip
    }

    // ------------------------------------------------------------------------------------------- C4-26

    /**
     * C4-26 (C-9, C-14, SR-07): for every failure case the detail code is from the fixed catalogue, and nothing
     * handed to the logger or present in {@code Result.toString()} contains the token, the JWT,
     * {@code Authorization}, the IF-30 path, a query string, the host, the port, a body or an exception message.
     */
    @Test
    public void c426_redaction() throws Exception {
        String jwt = enqueueJwt();
        List<Result<?>> results = new ArrayList<>();
        server.enqueue(status(401));
        server.enqueue(status(401));
        results.add(gateway.readingsSince(1234, 10));                  // 401 → IF-30 refresh 401 → AUTH_401
        server.enqueue(status(500));
        results.add(gateway.lastModified());                           // IF-30 500
        enqueueJwt();
        server.enqueue(status(403));
        results.add(gateway.lastModified());                           // FORBIDDEN_403
        enqueueJwtWithGroups(Fixtures.READABLE_PLUS_TREATMENTS_CUD_GROUPS);
        results.add(gateway.permissions());                            // ok (BUG-15: from permissionGroups)
        enqueueJwtWithGroups("[\"malformed\"]");
        results.add(gateway.permissions());                            // NON_JSON (that JWT is kept)
        server.enqueue(json(200, "<html>secret-body-marker</html>"));
        results.add(gateway.readingsSince(5678, 10));                  // NON_JSON
        server.enqueue(json(200, "{\"status\":200,\"result\":[{\"identifier\":\"bad\",\"type\":\"sgv\"}]}"));
        results.add(gateway.readingsSince(1, 10));                     // ok + MAPPING_SKIPPED
        server.enqueue(new MockResponse.Builder().code(302)
                .addHeader("Location", "http://127.0.0.1:1/?q=secret-query-marker").build());
        results.add(gateway.lastModified());                           // REDIRECT_REFUSED
        server.enqueue(new MockResponse.Builder().code(200)
                .body(new Buffer().write(new byte[9 * 1024 * 1024])).build());
        results.add(gateway.readingsSince(1, 10));                     // RESPONSE_TOO_LARGE
        results.add(gateway.version("http://127.0.0.1:1/"));          // CONNECT_FAILED
        tokens.token = null;
        gateway.configure(baseUrl(), tokens);
        results.add(gateway.lastModified());                           // TOKEN_MISSING

        Set<String> catalogue = new HashSet<>();
        for (Field f : DetailCode.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                catalogue.add((String) f.get(null));
            }
        }
        String host = server.getHostName().toLowerCase(Locale.ROOT);
        String port = Integer.toString(server.getPort());
        List<String> forbidden = List.of(Fakes.FAKE_TOKEN, jwt, "authorization", "bearer",
                "/api/v2/authorization/request/", "?", "secret-body-marker", "secret-query-marker", host,
                "127.0.0.1", port, "exception", "http:", "://", "api:", "*:*", "malformed");
        for (Result<?> r : results) {
            if (!r.ok()) {
                assertTrue(r.detailCode(), catalogue.contains(r.detailCode()));
            }
            assertClean(r.toString(), forbidden);
        }
        // Debug timing lines: a number may by chance contain the port digits, so the port is checked by the strict
        // shape instead (no ':' or '/' can appear in a line of this shape).
        List<String> forbiddenInDebug = new ArrayList<>(forbidden);
        forbiddenInDebug.remove(port);
        List<String> debug = new ArrayList<>();
        synchronized (logger.lines) {
            assertFalse(logger.lines.isEmpty());
            for (String line : logger.lines) {
                if (line.startsWith(DEBUG_TIMING)) {
                    String message = line.substring(DEBUG_TIMING.length());
                    assertClean(message, forbiddenInDebug);
                    assertTrue(line, message.matches(TIMING_LINE));
                    debug.add(message);
                    continue;
                }
                assertClean(line, forbidden);
                assertTrue(line, line.matches("[A-Z_]+(:[A-Z0-9_:]+)?"));
            }
        }
        // Debug-only per-call timing: one line per exchange with the call name, status class or failure kind.
        assertTrue(debug.toString(), debug.stream().anyMatch(l -> l.matches("IF-30 jwt \\d+ms 2xx len=\\d+")));
        assertTrue(debug.toString(), debug.stream().anyMatch(l -> l.matches("IF-30 jwt \\d+ms 5xx")));
        assertTrue(debug.toString(), debug.stream().anyMatch(l -> l.matches("IF-34 sgv \\d+ms 4xx")));
        assertTrue(debug.toString(), debug.stream().anyMatch(l -> l.matches("IF-34 sgv \\d+ms mapped")));
        assertTrue(debug.toString(), debug.stream().anyMatch(l -> l.matches("IF-33 lastModified \\d+ms 4xx")));
        assertTrue(debug.toString(),
                debug.stream().anyMatch(l -> l.matches("IF-33 lastModified \\d+ms " + DetailCode.REDIRECT_REFUSED)));
        assertTrue(debug.toString(),
                debug.stream().anyMatch(l -> l.matches("IF-34 sgv \\d+ms " + DetailCode.RESPONSE_TOO_LARGE)));
        assertTrue(debug.toString(),
                debug.stream().anyMatch(l -> l.matches("IF-31 version \\d+ms " + DetailCode.CONNECT_FAILED)));
    }

    /** Prefix the fake logger gives the debug timing lines of {@link NsHttp}. */
    private static final String DEBUG_TIMING = "DEBUG " + NsHttp.TIMING_TAG + " ";
    /** Shape of a debug timing line: constant call name, duration, result word, optional decoded length. */
    private static final String TIMING_LINE = "IF-\\d+ \\w+ \\d+ms \\w+( len=\\d+)?";

    /** Every Endpoint label has the {@code IF-<n> <name>} shape (no URL part can slip into a label). */
    @Test
    public void debugTiming_endpointLabelsAndResultWords() {
        for (Endpoint e : Endpoint.values()) {
            assertTrue(e.label(), e.label().matches("IF-\\d+ \\w+"));
            assertTrue(NsHttp.timingLine(e, System.nanoTime(), "2xx").matches(TIMING_LINE));
        }
        assertEquals("2xx", NsHttp.resultClass(HttpReply.status(204, null)));
        assertEquals("5xx", NsHttp.resultClass(HttpReply.status(503, null)));
        assertEquals(DetailCode.TIMEOUT,
                NsHttp.resultClass(HttpReply.failed(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT)));
        assertEquals("NO_INTERNET", NsHttp.resultClass(HttpReply.failed(FetchOutcome.NO_INTERNET, null)));
    }

    private static void assertClean(String text, List<String> forbidden) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String f : forbidden) {
            assertFalse("'" + f + "' leaked into: " + text, lower.contains(f.toLowerCase(Locale.ROOT)));
        }
    }
}
