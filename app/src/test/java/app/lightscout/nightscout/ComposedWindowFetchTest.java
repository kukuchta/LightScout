package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.usecases.common.SlicedWindowFetcher;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;

/**
 * BUG-18 S-B F2: the real {@link OkHttpNightscoutGateway} composed with the real {@link SlicedWindowFetcher}
 * against a simulated Nightscout ≤ 15.0.x v3 search (filters in query order, the last filter per field wins —
 * {@code filter[field] = item} — ascending sort, limit). Lives in {@code :app} tests because {@code :nightscout}
 * may not see {@code :usecases} (CP-00 §3); the package is {@code app.lightscout.nightscout} so the JVM test can use
 * the package-private test transport (cleartext to mockwebserver only, CP-00A R-40).
 */
public class ComposedWindowFetchTest {

    private static final long DAY_MS = 86_400_000L;
    /** End of the data set (2030-01-01T00:00Z). */
    private static final long END = 1_893_456_000_000L;

    private MockWebServer server;
    private OkHttpNightscoutGateway gateway;
    private final List<HttpUrl> dataRequests = new ArrayList<>();
    /** 1-min readings ({@code true}) or 5-min readings. */
    private boolean dense;
    /** Simulates the pre-BUG-18 request order ({@code $gte} before {@code $lt}). */
    private boolean oldOrder;

    @Before
    public void start() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                HttpUrl url = request.getUrl();
                if (url.encodedPath().startsWith("/api/v2/authorization/request/")) {
                    long exp = END / 1000 + 8 * 3600;
                    return json("{\"token\":\"" + jwt(exp) + "\",\"exp\":" + exp
                            + ",\"permissionGroups\":[[\"*:*:read\"],[]]}");
                }
                synchronized (dataRequests) {
                    dataRequests.add(url);
                }
                return json("{\"status\":200,\"result\":[" + String.join(",", search(url)) + "]}");
            }
        });
        server.start();
        HttpSettings test = new HttpSettings(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4),
                Duration.ofSeconds(3), 8L * 1024 * 1024, 3, true);
        gateway = new OkHttpNightscoutGateway(new FixedClock(), new Online(), new SilentLogger(), test);
        gateway.configure(server.url("/").toString(), () -> Optional.of("fake-token-0000-for-tests".toCharArray()));
    }

    @After
    public void stop() {
        server.close();
    }

    /** Last-wins v3 search over sgv readings from {@code END − 40 d} to {@code END}. */
    private List<String> search(HttpUrl url) {
        int limit = 1000;
        Long gte = null;
        Long lt = null;
        String lastDateOp = null;
        List<String[]> dateClauses = new ArrayList<>();
        for (int i = 0; i < url.querySize(); i++) {
            String name = url.queryParameterName(i);
            String value = url.queryParameterValue(i);
            if ("limit".equals(name)) {
                limit = Integer.parseInt(value);
            } else if (name.startsWith("date$")) {
                dateClauses.add(new String[] {name.substring(5), value});
            }
        }
        if (oldOrder) {
            java.util.Collections.reverse(dateClauses);   // what the old client sent: $gte first, $lt last
        }
        for (String[] c : dateClauses) {
            lastDateOp = c[0];
            if ("gte".equals(c[0])) {
                gte = Long.parseLong(c[1]);
                lt = null;
            } else if ("lt".equals(c[0])) {
                lt = Long.parseLong(c[1]);
                gte = null;
            }
        }
        long step = dense ? 60_000L : 300_000L;
        List<String> out = new ArrayList<>();
        for (long t = END - 40 * DAY_MS; t < END && out.size() < limit; t += step) {
            if ((gte == null || t >= gte) && (lt == null || t < lt) && lastDateOp != null) {
                out.add(String.format(Locale.ROOT, "{\"identifier\":\"r%d\",\"type\":\"sgv\",\"sgv\":120,"
                        + "\"date\":%d,\"direction\":\"Flat\",\"device\":\"cgm\"}", t, t));
            }
        }
        return out;
    }

    /** The RD-1 shape (old order on a 15.0.x server: oldest 1000 documents for every window) costs 1 call. */
    @Test
    public void oldOrderOnLastWinsServer_stopsAfterOneCall_windowIgnored() {
        oldOrder = true;
        Instant from = Instant.ofEpochMilli(END - 10 * DAY_MS);
        SlicedWindowFetcher.Fetched<Reading> f = SlicedWindowFetcher.fetch(gateway::readingsWindow, from,
                from.plus(Duration.ofDays(1)), SlicedWindowFetcher.WINDOW_LIMIT);
        assertEquals("was 63 calls before BUG-18", 1, dataRequests.size());
        assertTrue(f.windowIgnored());
        assertFalse(f.fullyComplete());
        assertTrue(f.allItems().isEmpty());   // nothing outside the window is stored
    }

    /** Fixed order with dense 1-min data (1440 per day): one split, 3 calls, complete, every reading once. */
    @Test
    public void fixedOrder_dense1MinData_threeCalls_complete() {
        dense = true;
        Instant from = Instant.ofEpochMilli(END - 10 * DAY_MS);
        Instant to = from.plus(Duration.ofDays(1));
        SlicedWindowFetcher.Fetched<Reading> f = SlicedWindowFetcher.fetch(gateway::readingsWindow, from, to,
                SlicedWindowFetcher.WINDOW_LIMIT);
        assertEquals(3, dataRequests.size());
        assertTrue(f.fullyComplete());
        assertFalse(f.windowIgnored());
        // the two 12 h halves are complete and hold exactly the 1440 readings of the window
        long inHalves = f.slices().stream().filter(s -> s.complete()).mapToLong(s -> s.items().size()).sum();
        assertEquals(1440, inHalves);
        for (Reading r : f.allItems()) {
            assertFalse(r.time().isBefore(from));
            assertTrue(r.time().isBefore(to));
        }
    }

    /** Fixed order with 5-min data: one call per day, complete. */
    @Test
    public void fixedOrder_5MinData_oneCall_complete() {
        Instant from = Instant.ofEpochMilli(END - 10 * DAY_MS);
        SlicedWindowFetcher.Fetched<Reading> f = SlicedWindowFetcher.fetch(gateway::readingsWindow, from,
                from.plus(Duration.ofDays(1)), SlicedWindowFetcher.WINDOW_LIMIT);
        assertEquals(1, dataRequests.size());
        assertTrue(f.fullyComplete());
        assertEquals(288, f.allItems().size());
    }

    private static MockResponse json(String body) {
        return new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build();
    }

    private static String jwt(long expSeconds) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(("{\"exp\":" + expSeconds + "}").getBytes(StandardCharsets.UTF_8)) + ".fakesig";
    }

    private static final class FixedClock implements Clock {
        @Override
        public Instant now() {
            return Instant.ofEpochMilli(END);
        }

        @Override
        public long elapsedRealtimeMs() {
            return END;
        }

        @Override
        public ZoneId zone() {
            return ZoneOffset.UTC;
        }
    }

    private static final class Online implements Connectivity {
        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public void addListener(Consumer<Boolean> l) {
            // not needed
        }
    }

    private static final class SilentLogger implements Logger {
        @Override
        public void event(EventCode code) {
        }

        @Override
        public void event(EventCode code, String detail) {
        }

        @Override
        public void crash(String exceptionClass, String topAppFrame) {
        }

        @Override
        public void debug(String tag, String message) {
        }
    }
}
