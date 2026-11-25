package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;

import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

/**
 * Base of the TST-04 contract tests: a {@code mockwebserver3} server on localhost (the only network peer — CP-00 §8,
 * GR-8), fakes for IF-15 / IF-19 / IF-61 / IF-66 and the <b>test-only</b> client of CP-00A R-40 (plain http
 * allowed, short budgets so the suite stays under a minute). Production values are asserted in
 * {@link SupportRulesTest#productionSettings()} and {@link TransportContractTest}.
 */
public abstract class GatewayTestBase {

    /** Test-only transport: cleartext allowed for mockwebserver; budgets scaled down. */
    static final HttpSettings TEST_SETTINGS = new HttpSettings(Duration.ofSeconds(2), Duration.ofSeconds(3),
            Duration.ofSeconds(4), Duration.ofSeconds(3), 8L * 1024 * 1024, 3, true);

    MockWebServer server;
    Fakes.FakeClock clock;
    Fakes.FakeConnectivity connectivity;
    Fakes.RecordingLogger logger;
    Fakes.FakeTokenSource tokens;
    OkHttpNightscoutGateway gateway;

    @Before
    public void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        clock = new Fakes.FakeClock();
        connectivity = new Fakes.FakeConnectivity();
        logger = new Fakes.RecordingLogger();
        tokens = new Fakes.FakeTokenSource(Fakes.FAKE_TOKEN);
        gateway = newGateway(TEST_SETTINGS);
        gateway.configure(baseUrl(), tokens);
    }

    @After
    public void stopServer() {
        server.close();
    }

    OkHttpNightscoutGateway newGateway(HttpSettings settings) {
        return new OkHttpNightscoutGateway(clock, connectivity, logger, settings);
    }

    String baseUrl() {
        return server.url("/").toString();
    }

    /** Expiry 8 h after the fake clock (EV-04). */
    long jwtExpSeconds() {
        return clock.now().getEpochSecond() + 8 * 3600;
    }

    /** Enqueues an IF-30 200 with a fresh 8 h JWT (FX-25). */
    String enqueueJwt() {
        long exp = jwtExpSeconds();
        String jwt = Fixtures.jwt(exp);
        server.enqueue(json(200, Fixtures.jwtResponse(jwt, exp)));
        return jwt;
    }

    /**
     * Enqueues an IF-30 200 with a fresh 8 h JWT and the given {@code permissionGroups} (BUG-15).
     *
     * @param groupsJson JSON array of arrays of permission strings, or {@code null} to omit the member
     * @return the JWT
     */
    String enqueueJwtWithGroups(String groupsJson) {
        long exp = jwtExpSeconds();
        String jwt = Fixtures.jwt(exp);
        server.enqueue(json(200, Fixtures.jwtResponseWithGroups(jwt, exp, groupsJson)));
        return jwt;
    }

    static MockResponse json(int code, String body) {
        return new MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build();
    }

    static MockResponse status(int code) {
        return new MockResponse.Builder().code(code).build();
    }

    RecordedRequest take() throws InterruptedException {
        RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
        if (r == null) {
            throw new AssertionError("expected a request");
        }
        // SR-09 / C4-27: never an api-secret header, on any request.
        assertNull(r.getHeaders().get("api-secret"));
        return r;
    }

    /** Takes the IF-30 request and checks it (path, no Authorization). */
    RecordedRequest takeJwtRequest() throws InterruptedException {
        RecordedRequest r = take();
        assertEquals("/api/v2/authorization/request/" + Fakes.FAKE_TOKEN, r.getUrl().encodedPath());
        assertNull(r.getHeaders().get("Authorization"));
        return r;
    }

    static void assertFailure(Result<?> r, FetchOutcome outcome, String detail) {
        assertFalse(r.ok());
        assertNull(r.value());
        assertEquals(outcome, r.outcome());
        assertEquals(detail, r.detailCode());
    }
}
