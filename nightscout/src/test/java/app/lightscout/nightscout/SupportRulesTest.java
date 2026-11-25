package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneId;

import javax.net.ssl.SSLHandshakeException;

import org.junit.Test;

import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import okhttp3.HttpUrl;

/**
 * Pure rule tests: envelope (TM-21, C-6), M-11 sanitizer, wire time formats (IF-35, IF-40, 05 §3), JWT expiry
 * (C-2, SR-08), redirect origin rule on {@link HttpUrl} pairs (C-10, SR-17, CP-00A R-40) and C-5 transport
 * classification.
 */
public class SupportRulesTest {

    /** TM-21 (C-6): result read, unknown top-level fields ignored; bare arrays tolerated; non-JSON rejected. */
    @Test
    public void tm21_envelope() throws Exception {
        assertEquals(1, Json.resultArray("{\"status\":200,\"unknown\":{\"x\":1},\"result\":[{}]}").length());
        assertEquals(2, Json.resultArray("[{},{}]").length());
        assertEquals("15.0.6", Json.str(Json.resultObject(Fixtures.text("FX-22-version.json")), "version"));
        assertThrows(Json.BadJson.class, () -> Json.resultArray("<html>Not Nightscout</html>"));
        assertThrows(Json.BadJson.class, () -> Json.resultArray("{\"status\":200}"));
        assertThrows(Json.BadJson.class, () -> Json.resultObject("{\"status\":200,\"result\":[]}"));
        assertThrows(Json.BadJson.class, () -> Json.parse(""));
    }

    /** M-11: null stays null, short text unchanged, cap 200 without splitting a surrogate pair. */
    @Test
    public void m11_sanitizer() throws Exception {
        assertNull(TextSanitizer.clean(null));
        assertEquals("CareLink Follow", TextSanitizer.clean("CareLink Follow"));
        assertEquals("a b", TextSanitizer.clean("a\tb\u0000"));
        // IR-28: bidi overrides / isolates and zero-width characters (category Cf) are dropped
        assertEquals("evil.exe", TextSanitizer.clean("‮evil​‍.⁦exe⁩﻿"));
        // IR-35: supplementary format characters (tag characters, shorthand format controls) dropped; emoji kept
        assertEquals("AB", TextSanitizer.clean("A" + new String(Character.toChars(0xE0041)) + "B"));
        assertEquals("AB", TextSanitizer.clean(new String(Character.toChars(0xE0001)) + "A"
                + new String(Character.toChars(0xE007F)) + new String(Character.toChars(0x1BCA0)) + "B"));
        assertEquals("ok 😀", TextSanitizer.clean("ok 😀"));
        String longText = "x".repeat(199) + "😀" + "y".repeat(10);
        String cleaned = TextSanitizer.clean(longText);
        assertEquals(199, cleaned.length());
        assertEquals(200, TextSanitizer.clean("z".repeat(300)).length());
    }

    /** IF-40 (R-40): ceil to the next second; exact second kept; C4-18 example. */
    @Test
    public void ifUnmodifiedSinceRoundsUp() throws Exception {
        assertEquals("Tue, 14 Nov 2023 22:13:21 GMT", NsTime.ifUnmodifiedSince(1_700_000_000_123L));
        assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", NsTime.ifUnmodifiedSince(1_700_000_000_000L));
        assertEquals("Tue, 14 Nov 2023 22:13:21 GMT", NsTime.ifUnmodifiedSince(1_700_000_000_001L));
        assertEquals("Wed, 01 Jan 2031 00:00:00 GMT", NsTime.ifUnmodifiedSince(1_924_992_000_000L));
    }

    /** IF-35 / 05 §3: fixed-width ISO with millis; parse accepts Z and offsets. */
    @Test
    public void isoFormats() throws Exception {
        assertEquals("2030-01-01T00:00:00.000Z", NsTime.iso(Instant.ofEpochMilli(Fakes.T0)));
        assertEquals(Instant.ofEpochMilli(Fakes.T0), NsTime.parseIso("2030-01-01T01:00:00+01:00"));
        assertNull(NsTime.parseIso("yesterday"));
        assertEquals(60, NsTime.utcOffsetMinutes(ZoneId.of("Europe/Warsaw"), Instant.ofEpochMilli(Fakes.T0)));
        assertEquals(120, NsTime.utcOffsetMinutes(ZoneId.of("Europe/Warsaw"), Instant.parse("2030-07-01T00:00:00Z")));
    }

    /** C-2 / SR-08: exp from the unsigned payload; body exp as fallback; unknown → MIN_VALUE. */
    @Test
    public void jwtExpiry() throws Exception {
        assertEquals(1_900_000_000_000L, JwtExpiry.expMs(Fixtures.jwt(1_900_000_000L), null));
        assertEquals(1_800_000_000_000L, JwtExpiry.expMs("not-a-jwt", 1_800_000_000d));
        assertEquals(Long.MIN_VALUE, JwtExpiry.expMs("a.%%%.c", null));
    }

    /** C-10 / SR-17 / R-40 on HttpUrl pairs: only same origin over https. */
    @Test
    public void redirectOriginRule() throws Exception {
        Origin https = Origin.of(HttpUrl.get("https://ns.example.invalid/"));
        assertTrue(Origin.redirectAllowed(https, HttpUrl.get("https://ns.example.invalid/api/v3/x"), false));
        assertTrue(Origin.redirectAllowed(https, HttpUrl.get("https://NS.example.invalid:443/y"), false));
        assertFalse("https→http", Origin.redirectAllowed(https, HttpUrl.get("http://ns.example.invalid/x"), false));
        assertFalse("https→http even for the test client",
                Origin.redirectAllowed(https, HttpUrl.get("http://ns.example.invalid/x"), true));
        assertFalse("other host", Origin.redirectAllowed(https, HttpUrl.get("https://other.example.invalid/x"), false));
        assertFalse("other port", Origin.redirectAllowed(https, HttpUrl.get("https://ns.example.invalid:8443/"), false));
        assertFalse("no location", Origin.redirectAllowed(https, null, false));
        Origin http = Origin.of(HttpUrl.get("http://127.0.0.1:8080/"));
        assertFalse("cleartext needs the test client",
                Origin.redirectAllowed(http, HttpUrl.get("http://127.0.0.1:8080/x"), false));
        assertTrue(Origin.redirectAllowed(http, HttpUrl.get("http://127.0.0.1:8080/x"), true));
        assertEquals("Origin[masked]", https.toString());
    }

    /** C-5 (C4-21 DNS part): transport classification with / without a validated network. */
    @Test
    public void c5_transportClassification() throws Exception {
        assertReply(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK,
                ErrorClassifier.transport(new UnknownHostException(), false));
        assertReply(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK,
                ErrorClassifier.transport(new ConnectException(), false));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED,
                ErrorClassifier.transport(new UnknownHostException(), true));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED,
                ErrorClassifier.transport(new ConnectException(), true));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TLS,
                ErrorClassifier.transport(new SSLHandshakeException("x"), true));
        ConnectException multiRoute = new ConnectException();
        multiRoute.addSuppressed(new SSLHandshakeException("x"));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TLS, ErrorClassifier.transport(multiRoute, true));
        // BUG-01: the two shapes OkHttp 5 produced against a non-TLS peer
        ConnectException handshakeEof = new ConnectException();
        handshakeEof.initCause(new ConnectException());
        handshakeEof.addSuppressed(new SSLHandshakeException("x"));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TLS, ErrorClassifier.transport(handshakeEof, true));
        ConnectException handshakeTimeout = new ConnectException();
        handshakeTimeout.addSuppressed(new SocketTimeoutException());
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT,
                ErrorClassifier.transport(handshakeTimeout, true));
        ConnectException both = new ConnectException();
        both.addSuppressed(new SocketTimeoutException());
        both.addSuppressed(new SSLHandshakeException("x"));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TLS, ErrorClassifier.transport(both, true));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT,
                ErrorClassifier.transport(new SocketTimeoutException(), true));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT,
                ErrorClassifier.transport(new InterruptedIOException(), true));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED,
                ErrorClassifier.transport(new IOException(), true));
        assertReply(FetchOutcome.NS_NOT_RESPONDING, DetailCode.RESPONSE_TOO_LARGE,
                ErrorClassifier.transport(new ResponseTooLargeException(), false));
    }

    /** C-5 status classification of unexpected statuses. */
    @Test
    public void c5_statusClassification() throws Exception {
        assertEquals(DetailCode.AUTH_401, ErrorClassifier.status(401).detailCode());
        assertEquals(FetchOutcome.TOKEN_REJECTED, ErrorClassifier.status(403).outcome());
        assertEquals(DetailCode.FORBIDDEN_403, ErrorClassifier.status(403).detailCode());
        assertEquals(DetailCode.HTTP_5XX, ErrorClassifier.status(503).detailCode());
        assertEquals(DetailCode.NON_JSON, ErrorClassifier.status(404).detailCode());
        assertEquals(FetchOutcome.NS_NOT_RESPONDING, ErrorClassifier.status(404).outcome());
    }

    /** C-3 / C-11 / R-40: the approved production values. */
    @Test
    public void productionSettings() throws Exception {
        HttpSettings p = HttpSettings.PRODUCTION;
        assertEquals(10_000, p.connectTimeout().toMillis());
        assertEquals(15_000, p.readTimeout().toMillis());
        assertEquals(20_000, p.readCallTimeout().toMillis());
        assertEquals(15_000, p.writeCallTimeout().toMillis());
        assertEquals(8L * 1024 * 1024, p.maxBodyBytes());
        assertEquals(3, p.maxRedirects());
        assertFalse(p.allowCleartext());
    }

    private static void assertReply(FetchOutcome outcome, String detail, HttpReply reply) {
        assertTrue(reply.isFailure());
        assertEquals(outcome, reply.failure());
        assertEquals(detail, reply.failureDetail());
    }
}
