package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

/**
 * TST-04 auth contracts: IF-30 and the C-2 JWT rules (CP-03 §9.3 C4-02…C4-07, C4-25, C4-27; SR-02, SR-03, SR-13).
 */
public class AuthContractTest extends GatewayTestBase {

    private static final String LAST_MODIFIED = Fixtures.text("FX-24-last-modified.json");

    /** C4-02: IF-30 once; JWT reused while exp − now ≥ 30 min; refreshed when < 30 min. */
    @Test
    public void c402_jwtReusedThenRefreshedBeforeExpiry() throws Exception {
        String jwt1 = enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        assertTrue(gateway.lastModified().ok());
        takeJwtRequest();
        assertEquals("Bearer " + jwt1, take().getHeaders().get("Authorization"));
        assertEquals("Bearer " + jwt1, take().getHeaders().get("Authorization"));

        // exactly 30 min left → still reused (≥ 30 min)
        clock.now = clock.now.plusSeconds(8 * 3600 - 30 * 60);
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        assertEquals("Bearer " + jwt1, take().getHeaders().get("Authorization"));

        // 29 min left → refreshed before the call
        clock.now = clock.now.plusSeconds(60);
        String jwt2 = enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        takeJwtRequest();
        assertEquals("Bearer " + jwt2, take().getHeaders().get("Authorization"));
        assertEquals(6, server.getRequestCount());
    }

    /** C4-03 (C-2, C-4): 401 → one IF-30 refresh + one repeat; second 401 → TOKEN_REJECTED, nothing more. */
    @Test
    public void c403_single401RefreshThenTokenRejected() throws Exception {
        enqueueJwt();
        server.enqueue(status(401));
        String jwt2 = enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        takeJwtRequest();
        take();
        takeJwtRequest();
        assertEquals("Bearer " + jwt2, take().getHeaders().get("Authorization"));

        server.enqueue(status(401));
        enqueueJwt();
        server.enqueue(status(401));
        assertFailure(gateway.lastModified(), FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        assertEquals(7, server.getRequestCount());

        // JWT discarded on TOKEN_REJECTED (SR-03): the next call fetches IF-30 first.
        enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        for (int i = 0; i < 3; i++) {
            take();
        }
        takeJwtRequest();
    }

    /** C4-04: IF-30 401 → TOKEN_REJECTED; 5xx → NS_NOT_RESPONDING; JWT_REFRESH_FAIL logged with the code. */
    @Test
    public void c404_authorizationEndpointFailures() throws Exception {
        server.enqueue(status(401));
        assertFailure(gateway.lastModified(), FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        server.enqueue(status(500));
        assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.HTTP_5XX);
        server.enqueue(json(200, "{\"sub\":\"no-token-field\"}"));
        assertFailure(gateway.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.NON_JSON);
        assertEquals(3, server.getRequestCount());
        assertTrue(logger.contains(EventCode.JWT_REFRESH_FAIL.name() + ":" + DetailCode.AUTH_401));
        assertTrue(logger.contains(EventCode.JWT_REFRESH_FAIL.name() + ":" + DetailCode.HTTP_5XX));
    }

    /** C4-04: IF-30 timeout → NS_NOT_RESPONDING / TIMEOUT. */
    @Test
    public void c404_authorizationEndpointTimeout() throws Exception {
        OkHttpNightscoutGateway fast = newGateway(new HttpSettings(java.time.Duration.ofSeconds(1),
                java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(500),
                8L * 1024 * 1024, 3, true));
        fast.configure(baseUrl(), tokens);
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS)
                .body("{}").build());
        assertFailure(fast.lastModified(), FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
    }

    /** C4-05 (03 §6.4): 10 threads with no / expired JWT → exactly one IF-30 request (single-flight). */
    @Test
    public void c405_singleFlightRefresh() throws Exception {
        AtomicInteger jwtRequests = new AtomicInteger();
        long exp = jwtExpSeconds();
        String jwt = Fixtures.jwt(exp);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getUrl().encodedPath().startsWith("/api/v2/authorization/request/")) {
                    jwtRequests.incrementAndGet();
                    return new MockResponse.Builder().code(200).headersDelay(300, TimeUnit.MILLISECONDS)
                            .body(Fixtures.jwtResponse(jwt, exp)).build();
                }
                return json(200, LAST_MODIFIED);
            }
        });
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Result<?>>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return gateway.lastModified();
                }));
            }
            start.countDown();
            for (Future<Result<?>> f : futures) {
                assertTrue(f.get(10, TimeUnit.SECONDS).ok());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jwtRequests.get());
        assertEquals(11, server.getRequestCount());
    }

    /**
     * C4-05 style (BUG-15): with a fresh JWT held, IF-32 runs together with 10 concurrent data calls. All succeed,
     * IF-32 makes exactly one IF-30, and every data request carries either the old or the new JWT (the refresh is
     * serialised with {@code obtain}).
     */
    @Test
    public void if32RefreshConcurrentWithDataCalls() throws Exception {
        AtomicInteger jwtRequests = new AtomicInteger();
        long exp1 = jwtExpSeconds();
        long exp2 = exp1 + 1;
        String jwt1 = Fixtures.jwt(exp1);
        String jwt2 = Fixtures.jwt(exp2);
        List<String> dataAuth = Collections.synchronizedList(new ArrayList<>());
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getUrl().encodedPath().startsWith("/api/v2/authorization/request/")) {
                    if (jwtRequests.incrementAndGet() == 1) {
                        return json(200, Fixtures.jwtResponse(jwt1, exp1));
                    }
                    return new MockResponse.Builder().code(200).headersDelay(300, TimeUnit.MILLISECONDS)
                            .body(Fixtures.jwtResponseWithGroups(jwt2, exp2,
                                    Fixtures.READABLE_PLUS_TREATMENTS_CUD_GROUPS)).build();
                }
                dataAuth.add(request.getHeaders().get("Authorization"));
                return json(200, LAST_MODIFIED);
            }
        });
        assertTrue(gateway.lastModified().ok());       // holds jwt1
        assertEquals(1, jwtRequests.get());

        ExecutorService pool = Executors.newFixedThreadPool(11);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Result<Permissions>> perms = pool.submit(() -> {
                start.await();
                return gateway.permissions();
            });
            List<Future<Result<?>>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return gateway.lastModified();
                }));
            }
            start.countDown();
            Result<Permissions> p = perms.get(10, TimeUnit.SECONDS);
            assertTrue(p.ok());
            assertEquals("crud", p.value().letters().get(NsCollection.TREATMENTS));
            for (Future<Result<?>> f : futures) {
                assertTrue(f.get(10, TimeUnit.SECONDS).ok());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, jwtRequests.get());               // exactly one IF-30 for IF-32
        assertEquals(13, server.getRequestCount());
        assertEquals(11, dataAuth.size());
        for (String auth : dataAuth) {
            assertTrue(auth, ("Bearer " + jwt1).equals(auth) || ("Bearer " + jwt2).equals(auth));
        }
    }

    /** C4-06 (SR-02, IF-61): empty token → TOKEN_REJECTED, no request; the char[] is zero-filled after IF-30. */
    @Test
    public void c406_tokenSourceEmptyAndArrayWiped() throws Exception {
        tokens.token = null;
        assertFailure(gateway.lastModified(), FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
        assertFailure(gateway.permissions(), FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
        assertEquals(0, server.getRequestCount());

        tokens.token = Fakes.FAKE_TOKEN;
        enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        assertEquals(1, tokens.issued.size());
        for (char c : tokens.issued.get(0)) {
            assertEquals('\0', c);
        }
        takeJwtRequest();
    }

    /** IF-61 / R-20: a store failure while reading the token → STORAGE_ERROR, no request, no exception. */
    @Test
    public void tokenSourceStorageFailure() throws Exception {
        gateway.configure(baseUrl(), () -> {
            throw new StorageException("token", null);
        });
        assertFailure(gateway.lastModified(), FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        assertEquals(0, server.getRequestCount());
    }

    /** Unconfigured gateway: TOKEN_MISSING, no request; unusable configured URL → CONNECT_FAILED. */
    @Test
    public void unconfiguredOrBadUrl() throws Exception {
        OkHttpNightscoutGateway fresh = newGateway(TEST_SETTINGS);
        assertFailure(fresh.lastModified(), FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
        fresh.configure("::not a url::", tokens);
        assertFailure(fresh.readingsSince(0, 10), FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
        assertEquals(0, server.getRequestCount());
    }

    /** C4-07 (C-5): 403 on a JWT call → TOKEN_REJECTED / FORBIDDEN_403, no refresh; JWT discarded. */
    @Test
    public void c407_forbidden() throws Exception {
        // IF-32 no longer makes a JWT data call (BUG-15), so the 403 data call is IF-33 here
        enqueueJwt();
        server.enqueue(status(403));
        assertFailure(gateway.lastModified(), FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        assertEquals(2, server.getRequestCount());
        enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        take();
        take();
        takeJwtRequest();
    }

    /**
     * BUG-15 (C-2, SR-03): IF-32 always fetches a fresh IF-30 and the new JWT replaces the held one; an IF-30 403
     * → TOKEN_REJECTED / FORBIDDEN_403 and the held JWT is discarded (the next call fetches IF-30 again).
     */
    @Test
    public void if32FreshJwtAndForbidden() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        String jwt2 = enqueueJwt();
        assertTrue(gateway.permissions().ok());
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        takeJwtRequest();
        take();
        takeJwtRequest();
        assertEquals("Bearer " + jwt2, take().getHeaders().get("Authorization"));

        server.enqueue(status(403));
        assertFailure(gateway.permissions(), FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        takeJwtRequest();
        takeJwtRequest();
        assertEquals(7, server.getRequestCount());
    }

    /** C4-25 (C-13, SR-03, SR-19): configure(other origin) discards the JWT; nothing more goes to the old origin. */
    @Test
    public void c425_reconfigureDiscardsJwtAndSwitchesOrigin() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        assertTrue(gateway.lastModified().ok());
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            long exp = jwtExpSeconds();
            String otherJwt = Fixtures.jwt(exp);
            other.enqueue(json(200, Fixtures.jwtResponse(otherJwt, exp)));
            other.enqueue(json(200, LAST_MODIFIED));
            gateway.configure(other.url("/").toString(), tokens);
            assertTrue(gateway.lastModified().ok());
            assertEquals(2, server.getRequestCount());
            assertEquals(2, other.getRequestCount());
            RecordedRequest jwtReq = other.takeRequest();
            assertTrue(jwtReq.getUrl().encodedPath().startsWith("/api/v2/authorization/request/"));
            assertEquals("Bearer " + otherJwt, other.takeRequest().getHeaders().get("Authorization"));
        }
    }

    /** C4-27 (SR-09): no api-secret header — checked by {@code take()} on every request here. */
    @Test
    public void c427_noApiSecretHeader() throws Exception {
        enqueueJwt();
        server.enqueue(json(200, LAST_MODIFIED));
        gateway.lastModified();
        RecordedRequest jwtRequest = takeJwtRequest();
        RecordedRequest data = take();
        assertNull(jwtRequest.getHeaders().get("api-secret"));
        assertNull(data.getHeaders().get("api-secret"));
        assertFalse(data.getUrl().toString().contains("secret"));
    }
}
