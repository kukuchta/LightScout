package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import okhttp3.HttpUrl;

/**
 * BUG-18: IF-34 / IF-35 windows against a simulated Nightscout API v3 search ({@code /api/v3/entries},
 * {@code /api/v3/treatments}). The simulation follows {@code lib/api3/generic/search/input.js} (filters in query
 * order, {@code sort} ascending on the requested field) and {@code lib/api3/storage/mongoCollection/utils.js}:
 * <ul>
 *   <li>{@link Mode#LAST_WINS} — Nightscout ≤ 15.0.x: {@code filter[field] = item}, so of two filters on one field
 *       only the last one survives;</li>
 *   <li>{@link Mode#MERGING} — current {@code master}: operators of one field are merged into one object;</li>
 *   <li>{@link Mode#IGNORING} — a server that ignores the filter (always the oldest {@code limit} documents).</li>
 * </ul>
 */
public class WindowFilterContractTest extends GatewayTestBase {

    private static final long MIN5 = 300_000L;
    private static final long DAY_MS = 86_400_000L;
    /** „Now” of the data set; 40 days of 5-min readings end here. */
    private static final long NOW = Fakes.T0;
    private static final long FIRST = NOW - 40 * DAY_MS;

    enum Mode { LAST_WINS, MERGING, IGNORING }

    /** One filter clause in query order. */
    private record Clause(String field, String op, String value) {
    }

    /** Simulated server; counts documents returned per request. */
    private final class SimulatedNightscout extends Dispatcher {
        final Mode mode;
        final List<Integer> returned = new ArrayList<>();
        final AtomicInteger dataRequests = new AtomicInteger();

        SimulatedNightscout(Mode mode) {
            this.mode = mode;
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            HttpUrl url = request.getUrl();
            if (url.encodedPath().startsWith("/api/v2/authorization/request/")) {
                long exp = jwtExpSeconds();
                return json(200, Fixtures.jwtResponse(Fixtures.jwt(exp), exp));
            }
            dataRequests.incrementAndGet();
            List<String> docs = search(mode, url);
            returned.add(docs.size());
            return json(200, Fixtures.envelope(docs.toArray(new String[0])));
        }
    }

    /** The v3 search of the simulated server: filter (per mode), ascending sort on the time field, limit. */
    static List<String> search(Mode mode, HttpUrl url) {
        boolean treatments = url.encodedPath().endsWith("/treatments");
        String timeField = treatments ? "created_at" : "date";
        List<Clause> clauses = new ArrayList<>();
        int limit = 1000;
        for (int i = 0; i < url.querySize(); i++) {
            String name = url.queryParameterName(i);
            String value = url.queryParameterValue(i);
            if ("limit".equals(name)) {
                limit = Integer.parseInt(value);
            } else if (name.contains("$") && !name.startsWith("sort")) {
                int d = name.lastIndexOf('$');
                clauses.add(new Clause(name.substring(0, d), name.substring(d + 1), value));
            }
        }
        // field → list of (op, value) that the MongoDB query finally contains
        Map<String, List<Clause>> filter = new LinkedHashMap<>();
        if (mode != Mode.IGNORING) {
            for (Clause c : clauses) {
                if (mode == Mode.LAST_WINS || !filter.containsKey(c.field())) {
                    filter.put(c.field(), new ArrayList<>());
                }
                filter.get(c.field()).add(c);
            }
        }
        List<String> out = new ArrayList<>();
        long step = treatments ? 2 * 3_600_000L : MIN5;
        for (long t = FIRST; t < NOW && out.size() < limit; t += step) {
            if (matches(filter.get(timeField), t) && matchesType(filter.get("type"))) {
                out.add(treatments ? treatment(t) : sgv(t));
            }
        }
        return out;
    }

    private static boolean matchesType(List<Clause> type) {
        return type == null || type.stream().allMatch(c -> "sgv".equals(c.value()));
    }

    private static boolean matches(List<Clause> clauses, long t) {
        if (clauses == null) {
            return true;
        }
        for (Clause c : clauses) {
            long v = c.value().matches("\\d+") ? Long.parseLong(c.value()) : Instant.parse(c.value()).toEpochMilli();
            boolean ok = switch (c.op()) {
                case "gte" -> t >= v;
                case "gt" -> t > v;
                case "lt" -> t < v;
                case "lte" -> t <= v;
                default -> true;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String sgv(long t) {
        return String.format(Locale.ROOT, "{\"identifier\":\"r%d\",\"type\":\"sgv\",\"sgv\":120,\"date\":%d,"
                + "\"direction\":\"Flat\",\"device\":\"share2\"}", t, t);
    }

    private static String treatment(long t) {
        return String.format(Locale.ROOT, "{\"identifier\":\"t%d\",\"eventType\":\"Site Change\","
                + "\"created_at\":\"%s\",\"enteredBy\":\"uploader\"}", t, NsTime.iso(Instant.ofEpochMilli(t)));
    }

    private SimulatedNightscout serve(Mode mode) {
        SimulatedNightscout s = new SimulatedNightscout(mode);
        server.setDispatcher(s);
        return s;
    }

    private static Instant at(long ms) {
        return Instant.ofEpochMilli(ms);
    }

    // ------------------------------------------------------------------------------------------- root cause

    /**
     * Root cause: with the pre-BUG-18 order ({@code date$gte} then {@code date$lt}) a ≤ 15.0.x server keeps only
     * {@code date < to}: every window returns the same oldest 1000 documents of the collection (RD-1: 63 × the
     * same {@code len=280712}).
     */
    @Test
    public void rootCause_lastWinsServer_oldParameterOrder_returnsSameOldestPageForEveryWindow() {
        HttpUrl base = HttpUrl.get("https://ns.example.invalid/api/v3/entries");
        List<List<String>> pages = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            long from = NOW - (day + 1) * DAY_MS;
            HttpUrl u = base.newBuilder().addQueryParameter("type$eq", "sgv")
                    .addEncodedQueryParameter("date$gte", Long.toString(from))
                    .addEncodedQueryParameter("date$lt", Long.toString(from + DAY_MS))
                    .addQueryParameter("sort", "date").addQueryParameter("limit", "1000").build();
            pages.add(search(Mode.LAST_WINS, u));
        }
        assertEquals(1000, pages.get(0).size());
        assertTrue(pages.get(0).get(0).contains("\"date\":" + FIRST + ","));   // oldest documents of all
        assertEquals(pages.get(0), pages.get(1));
        assertEquals(pages.get(0), pages.get(2));
    }

    // ------------------------------------------------------------------------------------------- IF-34

    /** Fixed order ({@code date$lt} first, {@code date$gte} last) on a ≤ 15.0.x server: the window is honoured. */
    @Test
    public void lastWinsServer_readingsWindow_trimmedAndComplete() throws Exception {
        SimulatedNightscout s = serve(Mode.LAST_WINS);
        long from = NOW - 10 * DAY_MS;
        Result<Page<Reading>> r = gateway.readingsWindow(at(from), at(from + DAY_MS), 1000);
        assertTrue(r.ok());
        assertEquals(288, r.value().items().size());
        assertTrue(r.value().complete());
        for (Reading x : r.value().items()) {
            long t = x.time().toEpochMilli();
            assertTrue(t >= from && t < from + DAY_MS);
        }
        assertEquals(List.of(1000), s.returned);   // the server sent `limit` documents from `from` on

        takeJwtRequest();
        HttpUrl u = take().getUrl();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < u.querySize(); i++) {
            names.add(u.queryParameterName(i));
        }
        assertTrue("upper bound first, lower bound last", names.indexOf("date$lt") < names.indexOf("date$gte"));
        assertEquals("date", u.queryParameter("sort"));
    }

    /** A server that merges both bounds (Nightscout master) returns only the window — same result. */
    @Test
    public void mergingServer_readingsWindow_onlyWindowReturned() {
        SimulatedNightscout s = serve(Mode.MERGING);
        long from = NOW - 10 * DAY_MS;
        Result<Page<Reading>> r = gateway.readingsWindow(at(from), at(from + DAY_MS), 1000);
        assertEquals(288, r.value().items().size());
        assertTrue(r.value().complete());
        assertEquals(List.of(288), s.returned);
    }

    /** A window with more than `limit` documents stays incomplete on both servers (split by the use cases). */
    @Test
    public void fullWindow_incomplete_onBothServers() {
        for (Mode mode : List.of(Mode.LAST_WINS, Mode.MERGING)) {
            serve(mode);
            long from = NOW - 10 * DAY_MS;
            Result<Page<Reading>> r = gateway.readingsWindow(at(from), at(from + DAY_MS), 100);
            assertEquals(mode.name(), 100, r.value().items().size());
            assertFalse(mode.name(), r.value().complete());
        }
    }

    /** A server ignoring the filter: nothing outside the window is passed on, the page is incomplete and empty. */
    @Test
    public void ignoringServer_readingsWindow_emptyIncompletePage_debugLine() {
        serve(Mode.IGNORING);
        long from = NOW - 10 * DAY_MS;
        Result<Page<Reading>> r = gateway.readingsWindow(at(from), at(from + DAY_MS), 1000);
        assertTrue(r.ok());
        assertTrue(r.value().items().isEmpty());
        assertFalse(r.value().complete());
        assertTrue(logger.contains("DEBUG " + OkHttpNightscoutGateway.WINDOW_TAG
                + " IF-34 sgv window-ignored before=1000 of=1000"));
    }

    /** The newest window (no document at or after `to` exists yet) is complete when the page is short. */
    @Test
    public void lastWinsServer_newestWindow_shortPageComplete() {
        serve(Mode.LAST_WINS);
        Result<Page<Reading>> r = gateway.readingsWindow(at(NOW - DAY_MS), at(NOW + 600_000L), 1000);
        assertEquals(288, r.value().items().size());
        assertTrue(r.value().complete());
    }

    // ------------------------------------------------------------------------------------------- IF-35

    /** IF-35 has the same two-filters-on-one-field shape: fixed the same way. */
    @Test
    public void lastWinsServer_treatmentsWindow_trimmedAndComplete() throws Exception {
        SimulatedNightscout s = serve(Mode.LAST_WINS);
        long from = NOW - 10 * DAY_MS;
        Result<Page<Entry>> r = gateway.entriesWindow(at(from), at(from + DAY_MS), 1000);
        assertEquals(12, r.value().items().size());
        assertTrue(r.value().complete());
        for (Entry e : r.value().items()) {
            long t = e.time().toEpochMilli();
            assertTrue(t >= from && t < from + DAY_MS);
        }
        assertEquals(List.of(120), s.returned);   // 10 days × 12 per day from `from` on
        takeJwtRequest();
        HttpUrl u = take().getUrl();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < u.querySize(); i++) {
            names.add(u.queryParameterName(i));
        }
        assertTrue(names.indexOf("created_at$lt") < names.indexOf("created_at$gte"));
    }

    @Test
    public void mergingServer_treatmentsWindow() {
        SimulatedNightscout s = serve(Mode.MERGING);
        long from = NOW - 10 * DAY_MS;
        Result<Page<Entry>> r = gateway.entriesWindow(at(from), at(from + Duration.ofDays(1).toMillis()), 1000);
        assertEquals(12, r.value().items().size());
        assertTrue(r.value().complete());
        assertEquals(List.of(12), s.returned);
    }

    // ------------------------------------------------------------------------------------------- S-B round 1

    private static String site(String id, String createdAt) {
        return "{\"identifier\":\"" + id + "\",\"eventType\":\"Site Change\",\"created_at\":\"" + createdAt
                + "\",\"enteredBy\":\"uploader\"}";
    }

    /**
     * F1: a full IF-35 page sorted by the server as strings, with a mixed-format {@code created_at}
     * ({@code +05:00} sorts after {@code 05:00Z} although it is 04:00Z) and a last document after {@code to}.
     * The old rule („any document ≥ to”) called it complete — a set-difference replace could then delete a
     * treatment of the window that did not fit into the page. Now: incomplete (upsert only).
     */
    @Test
    public void f1_outOfOrderMixedFormatPage_fullPage_incomplete_noReplace() {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.envelope(
                site("a", "2030-01-01T01:00:00.000Z"),
                site("b", "2030-01-01T05:00:00.000Z"),
                site("c", "2030-01-01T09:00:00+05:00"),
                site("d", "2030-01-02T01:00:00.000Z"))));
        Instant from = at(NOW);
        Result<Page<Entry>> r = gateway.entriesWindow(from, from.plus(Duration.ofDays(1)), 4);
        assertEquals(3, r.value().items().size());
        assertFalse("not verifiably sorted → never complete when full", r.value().complete());
    }

    /** F1 control: the same page in time order is complete (its last document is after {@code to}). */
    @Test
    public void f1_sortedFullPage_lastAfterTo_complete() {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.envelope(
                site("a", "2030-01-01T01:00:00.000Z"),
                site("c", "2030-01-01T09:00:00+05:00"),
                site("b", "2030-01-01T05:00:00.000Z"),
                site("d", "2030-01-02T01:00:00.000Z"))));
        Instant from = at(NOW);
        Result<Page<Entry>> r = gateway.entriesWindow(from, from.plus(Duration.ofDays(1)), 4);
        assertEquals(3, r.value().items().size());
        assertTrue(r.value().complete());
    }

    /** F1: a document ≥ {@code to} that is not the last one does not make a full page complete. */
    @Test
    public void f1_endDocumentNotLast_incomplete() {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.envelope(
                site("a", "2030-01-01T01:00:00.000Z"),
                site("d", "2030-01-02T01:00:00.000Z"),
                site("b", "2030-01-01T05:00:00.000Z"))));
        Instant from = at(NOW);
        Result<Page<Entry>> r = gateway.entriesWindow(from, from.plus(Duration.ofDays(1)), 3);
        assertFalse(r.value().complete());
        assertEquals(2, r.value().items().size());
    }

    /**
     * F6: a treatment without {@code created_at} cannot be trimmed on the raw time; it is mapped from {@code date}
     * and dropped after mapping when outside the window (also makes the page unsorted → incomplete when full).
     */
    @Test
    public void f6_mappedItemOutsideWindow_dropped() {
        enqueueJwt();
        long outside = NOW + 3 * DAY_MS;
        server.enqueue(json(200, Fixtures.envelope(
                site("a", "2030-01-01T01:00:00.000Z"),
                "{\"identifier\":\"x\",\"eventType\":\"Site Change\",\"date\":" + outside
                        + ",\"enteredBy\":\"uploader\"}")));
        Instant from = at(NOW);
        Result<Page<Entry>> r = gateway.entriesWindow(from, from.plus(Duration.ofDays(1)), 1000);
        assertEquals(1, r.value().items().size());
        assertEquals("a", r.value().items().get(0).id());
        assertTrue(r.value().complete());   // short page
    }

    /** N3: a full page with a document of unknown filter time is not verifiably sorted → incomplete. */
    @Test
    public void n3_unknownTimeDocument_fullPage_lastAfterTo_incomplete() {
        enqueueJwt();
        server.enqueue(json(200, Fixtures.envelope(
                site("a", "2030-01-01T01:00:00.000Z"),
                "{\"identifier\":\"x\",\"eventType\":\"Site Change\",\"date\":" + (NOW + 7_200_000L)
                        + ",\"enteredBy\":\"uploader\"}",
                site("d", "2030-01-02T01:00:00.000Z"))));
        Instant from = at(NOW);
        Result<Page<Entry>> r = gateway.entriesWindow(from, from.plus(Duration.ofDays(1)), 3);
        assertFalse(r.value().complete());
        assertEquals(2, r.value().items().size());   // a and x (mapped from `date`, inside the window)
    }
}
