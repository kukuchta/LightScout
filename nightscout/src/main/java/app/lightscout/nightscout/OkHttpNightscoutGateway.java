package app.lightscout.nightscout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.json.JSONArray;
import org.json.JSONObject;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Page;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.domain.port.WriteAck;
import okhttp3.HttpUrl;

/**
 * IF-07 {@link NightscoutGateway} over OkHttp 5 (CP-03; CMP-20 NightscoutClient, CMP-21 Mappers, CMP-22
 * ErrorClassifier), for the Nightscout contracts IF-30…IF-41.
 *
 * <p>Every method is blocking, must run off the main thread (monitor or io executor, CP-00 §4), executes one
 * Nightscout call (plus the C-2 JWT fetch / single refresh, and the IF-39 read after an IF-40 without
 * {@code lastModified} — CP-00A R-40) and never throws: every failure is a {@link Result} with a
 * {@link FetchOutcome} and a {@link DetailCode} (CP-00 §5, SR-37).
 *
 * <p>Security: https only (C-1), same-origin https redirects only and {@code Authorization} only to the configured
 * origin (C-10, C-13, SR-19), 8 MB decompressed cap (C-11), JWT in memory only (C-2, SR-03), access token read on
 * demand and wiped after building the IF-30 request (SR-02), no {@code api-secret} header (SR-09), nothing
 * sensitive logged (SR-07, C-9, C-14). Version / API / permission policy is applied by the use cases; this class
 * returns exactly what the server sent (CP-00A R-50, 05 §5).
 *
 * <p>Thread-safe: {@link #configure} may be called from any thread; it atomically replaces the connection and
 * discards the JWT (WF-05, SR-03, SR-13).
 */
public final class OkHttpNightscoutGateway implements NightscoutGateway {

    /** C-7: at most 1000 documents per call. */
    static final int MAX_LIMIT = 1000;
    /** IF-36: device status page size. */
    static final int DEVICE_STATUS_LIMIT = 300;
    /** Logcat tag of the debug-only BUG-18 line of a window the server did not honour (counts only, SR-07). */
    static final String WINDOW_TAG = "NsWindow";


    private final Clock clock;
    private final Logger logger;
    private final NsHttp http;
    private volatile Connection connection;

    /**
     * Creates the gateway with the approved production transport (C-3 timeouts, C-11 cap, https only).
     * It is unconfigured until {@link #configure} is called.
     *
     * @param clock        IF-15 (JWT expiry, {@code utcOffset})
     * @param connectivity IF-19 (separates {@code NO_INTERNET} from {@code NS_NOT_RESPONDING}, C-5)
     * @param logger       IF-66 (event codes only)
     */
    public OkHttpNightscoutGateway(Clock clock, Connectivity connectivity, Logger logger) {
        this(clock, connectivity, logger, HttpSettings.PRODUCTION);
    }

    /**
     * Creates the gateway with explicit transport settings.
     *
     * @param clock        IF-15
     * @param connectivity IF-19
     * @param logger       IF-66
     * @param settings     transport budget and policy
     */
    OkHttpNightscoutGateway(Clock clock, Connectivity connectivity, Logger logger, HttpSettings settings) {
        this.clock = clock;
        this.logger = logger;
        this.http = new NsHttp(settings, connectivity, logger);
    }

    /** One configured server: base URL, origin, token source and its own JWT holder. */
    private static final class Connection {
        private final HttpUrl base;
        private final Origin origin;
        private final TokenSource tokens;
        private JwtHolder jwt;

        Connection(HttpUrl base, TokenSource tokens) {
            this.base = base;
            this.origin = base == null ? null : Origin.of(base);
            this.tokens = tokens;
        }
    }

    /** Budget and 401 behaviour of a call class (C-2, C-3, C-4, 05 §2 timeouts). */
    private enum CallKind {
        /** Sync / verification reads IF-33…IF-37: 20 s, one JWT refresh + repeat on 401. */
        READ(false, true),
        /** IF-39 (S-02 edit load, conflict check): 15 s, one JWT refresh + repeat on 401. */
        USER_READ(true, true),
        /** IF-38, IF-40, IF-41: 15 s, never repeated (C-4). */
        WRITE(true, false);

        private final boolean shortBudget;
        private final boolean repeatAfter401;

        CallKind(boolean shortBudget, boolean repeatAfter401) {
            this.shortBudget = shortBudget;
            this.repeatAfter401 = repeatAfter401;
        }
    }

    /** Handles the status codes a contract defines; returns {@code null} for anything else. */
    private interface ReplyHandler<T> {
        Result<T> handle(int code, String body) throws Json.BadJson;
    }

    // ------------------------------------------------------------------------------------------- configuration

    @Override
    public void configure(String baseUrl, TokenSource tokens) {
        Connection c = new Connection(parseBase(baseUrl), tokens);
        c.jwt = new JwtHolder(clock, logger, () -> fetchJwt(c));
        connection = c;
    }

    // ------------------------------------------------------------------------------------------- IF-31 version

    @Override
    public Result<ServerInfo> version(String baseUrl) {
        try {
            HttpUrl base = parseBase(baseUrl);
            if (base == null) {
                return Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
            }
            HttpUrl url = api(base, "api", "v3", "version").build();
            HttpReply reply = http.execute(HttpCall.get(Endpoint.IF31_VERSION, url, Origin.of(base)));
            if (reply.isFailure()) {
                return ErrorClassifier.failure(reply);
            }
            if (reply.code() >= 500) {
                return ErrorClassifier.status(reply.code());
            }
            if (!reply.isSuccess()) {
                return Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
            }
            try {
                JSONObject result = Json.resultObject(reply.body());
                String version = Json.str(result, "version");
                if (version == null || version.isBlank()) {
                    return Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
                }
                return Result.ok(new ServerInfo(version.trim(), Json.str(result, "apiVersion")));
            } catch (Json.BadJson e) {
                return Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
            }
        } catch (RuntimeException | Error e) {
            return ErrorClassifier.unexpected();
        }
    }

    // ------------------------------------------------------------------------------------------- IF-32, IF-33

    /**
     * IF-32 permissions, derived from the IF-30 {@code permissionGroups} with Nightscout's shiro-trie semantics
     * ({@link ShiroPermissions}; BUG-15, D-060). {@code /api/v3/status} is no longer read: its
     * {@code apiPermissions} is wrong on Nightscout master ({@code lib/api3/specific/status.js} checks
     * {@code api:undefined:<op>}, so only wildcard grants show up — RISK-21).
     *
     * <p>Every call performs one fresh IF-30 request (which also replaces the held JWT), so the result reflects
     * the subject's current roles, like the per-request {@code /status} resolution did; that is one request per
     * call (before: one or two — IF-30 when needed, then {@code /status}). IF-30 failures map as for any JWT
     * fetch: 401 → {@code AUTH_401}, 403 → {@code FORBIDDEN_403}, 5xx → {@code HTTP_5XX}, empty token →
     * {@code TOKEN_MISSING}. A 200 whose
     * {@code permissionGroups} is missing or not an array of string arrays → {@code NS_NOT_RESPONDING} /
     * {@code NON_JSON}, the same class as a malformed body.
     */
    @Override
    public Result<Permissions> permissions() {
        try {
            Connection c = connection;
            if (c == null) {
                return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
            }
            if (c.base == null) {
                return Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
            }
            Result<JwtHolder.Jwt> jwt = c.jwt.refresh();
            if (!jwt.ok()) {
                return Result.fail(jwt.outcome(), jwt.detailCode());
            }
            List<List<String>> groups = jwt.value().permissionGroups();
            if (groups == null) {
                return ErrorClassifier.nonJson();
            }
            return Result.ok(ShiroPermissions.of(groups).toPermissions());
        } catch (RuntimeException | Error e) {
            return ErrorClassifier.unexpected();
        }
    }

    @Override
    public Result<Map<NsCollection, Long>> lastModified() {
        return authed(Endpoint.IF33_LAST_MODIFIED, "GET", b -> api(b, "api", "v3", "lastModified"), null, null, CallKind.READ, (code, body) -> {
            if (code != 200) {
                return null;
            }
            JSONObject collections = Json.obj(Json.resultObject(body), "collections");
            if (collections == null) {
                throw new Json.BadJson();
            }
            Map<NsCollection, Long> out = new EnumMap<>(NsCollection.class);
            for (NsCollection c : NsCollection.values()) {
                Double ms = Json.numberOrNull(collections, collectionName(c));
                if (ms != null) {
                    out.put(c, Math.round(ms));
                }
            }
            return Result.ok(Map.copyOf(out));
        });
    }

    // ------------------------------------------------------------------------------------------- IF-34, IF-35

    @Override
    public Result<Page<Reading>> readingsSince(long fromMsExclusive, int limit) {
        int n = clamp(limit);
        return entriesCall("sgv", b -> b.addEncodedQueryParameter("date$gt", Long.toString(fromMsExclusive)), n,
                array -> page(log(NsCollection.ENTRIES, EntriesMapper.readings(array)), n));
    }

    @Override
    public Result<Page<Entry>> meterBgSince(long fromMsExclusive, int limit) {
        int n = clamp(limit);
        return entriesCall("mbg", b -> b.addEncodedQueryParameter("date$gt", Long.toString(fromMsExclusive)), n,
                array -> page(log(NsCollection.ENTRIES, EntriesMapper.meterBg(array)), n));
    }

    @Override
    public Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit) {
        int n = clamp(limit);
        return entriesCall("sgv", window(from, to), n, array -> windowPage(Endpoint.IF34_SGV, array, from, to, n,
                OkHttpNightscoutGateway::entryDateMs, a -> log(NsCollection.ENTRIES, EntriesMapper.readings(a)),
                Reading::time));
    }

    @Override
    public Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit) {
        int n = clamp(limit);
        return entriesCall("mbg", window(from, to), n, array -> windowPage(Endpoint.IF34_MBG, array, from, to, n,
                OkHttpNightscoutGateway::entryDateMs, a -> log(NsCollection.ENTRIES, EntriesMapper.meterBg(a)),
                Entry::time));
    }

    /**
     * IF-35 window. BUG-18: the upper bound is sent first and the lower bound last — Nightscout ≤ 15.0.x keeps only
     * the last filter per field ({@code filter[field] = item} in {@code lib/api3/storage/mongoCollection/utils.js}),
     * so it then honours {@code $gte}; the window is trimmed and its completeness decided here
     * ({@link #windowPage}).
     */
    @Override
    public Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit) {
        int n = clamp(limit);
        return authed(Endpoint.IF35_TREATMENTS, "GET", b -> api(b, "api", "v3", "treatments")
                        .addEncodedQueryParameter("created_at$lt", NsTime.iso(to))
                        .addEncodedQueryParameter("created_at$gte", NsTime.iso(from))
                        .addEncodedQueryParameter("sort", "created_at")
                        .addEncodedQueryParameter("limit", Integer.toString(n)),
                null, null, CallKind.READ, arrayHandler(array -> windowPage(Endpoint.IF35_TREATMENTS, array, from, to,
                        n, OkHttpNightscoutGateway::createdAtMs,
                        a -> log(NsCollection.TREATMENTS, TreatmentsMapper.map(a)), Entry::time)));
    }

    // ------------------------------------------------------------------------------------------- IF-36, IF-37

    @Override
    public Result<List<DeviceStatus>> deviceStatusSince(Instant from) {
        return authed(Endpoint.IF36_DEVICESTATUS, "GET", b -> api(b, "api", "v3", "devicestatus")
                        .addEncodedQueryParameter("created_at$gte", NsTime.iso(from))
                        .addEncodedQueryParameter("sort$desc", "created_at")
                        .addEncodedQueryParameter("limit", Integer.toString(DEVICE_STATUS_LIMIT)),
                null, null, CallKind.READ, arrayHandler(
                        array -> Result.ok(log(NsCollection.DEVICESTATUS, DeviceStatusMapper.map(array)).items())));
    }

    /**
     * IF-36 latest profile. A server without any profile document (or whose newest profile cannot be mapped,
     * logged as {@code MAPPING_SKIPPED}) yields {@code ok(null)}: there is no basal baseline (ADR-031).
     *
     * @return the newest profile, or {@code ok(null)} when there is none
     */
    @Override
    public Result<Profile> latestProfile() {
        return authed(Endpoint.IF36_PROFILE, "GET", b -> api(b, "api", "v3", "profile")
                        .addEncodedQueryParameter("sort$desc", "created_at")
                        .addEncodedQueryParameter("limit", "1"),
                null, null, CallKind.READ, arrayHandler(array -> {
                    if (array.length() == 0) {
                        return Result.ok(null);
                    }
                    try {
                        if (array.opt(0) instanceof JSONObject doc) {
                            return Result.ok(ProfileMapper.map(doc, clock.zone()));
                        }
                    } catch (Json.InvalidDocument e) {
                        // skipped and counted below (M-8)
                    }
                    logger.event(EventCode.MAPPING_SKIPPED, NsCollection.PROFILE.name() + ":1");
                    return Result.ok(null);
                }));
    }

    @Override
    public Result<Page<Change>> history(NsCollection c, long cursorMs, int limit) {
        if (c == null) {
            return ErrorClassifier.unexpected();
        }
        int n = clamp(limit);
        return authed(Endpoint.IF37_HISTORY, "GET", b -> api(b, "api", "v3", collectionName(c), "history", Long.toString(cursorMs))
                        .addEncodedQueryParameter("limit", Integer.toString(n)),
                null, null, CallKind.READ, arrayHandler(array -> page(log(c, HistoryMapper.map(c, array)), n)));
    }

    // ------------------------------------------------------------------------------------------- IF-38…IF-41

    @Override
    public Result<WriteAck> create(Entry e) {
        String body;
        try {
            body = WritePayload.json(e, clock.zone());
        } catch (WritePayload.NotWritable | RuntimeException ex) {
            return Result.fail(FetchOutcome.OK, DetailCode.REJECTED_400_422);
        }
        return authed(Endpoint.IF38_CREATE, "POST", b -> api(b, "api", "v3", "treatments"), body, null, CallKind.WRITE, (code, text) -> {
            if (code == 200 || code == 201) {
                // The server stored the document: an unreadable creation body still means success (GIR-3);
                // srvModified is then unknown (-1).
                JSONObject j = objectOrNull(text);
                String id = j == null ? null : Json.str(j, "identifier");
                Double lastModified = j == null ? null : Json.numberOrNull(j, "lastModified");
                boolean dedup = j != null && Boolean.TRUE.equals(Json.opt(j, "isDeduplication"));
                return Result.ok(new WriteAck(id == null || id.isBlank() ? e.id() : id,
                        lastModified == null ? -1L : Math.round(lastModified), dedup));
            }
            return rejected(code);
        });
    }

    @Override
    public Result<Entry> get(String id) {
        if (id == null || id.isBlank()) {
            return Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
        }
        return authed(Endpoint.IF39_GET, "GET", b -> api(b, "api", "v3", "treatments", id), null, null, CallKind.USER_READ,
                (code, text) -> {
            if (code == 200) {
                JSONObject doc = Json.resultObject(text);
                List<Entry> entries;
                try {
                    entries = TreatmentsMapper.map(doc);
                } catch (Json.InvalidDocument ex) {
                    throw new Json.BadJson();
                }
                if (entries.isEmpty()) {
                    throw new Json.BadJson();
                }
                return Result.ok(entries.get(0));
            }
            if (code == 404 || code == 410) {
                return Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
            }
            return null;
        });
    }

    @Override
    public Result<WriteAck> update(Entry e, long expectedSrvModifiedMs) {
        String body;
        try {
            body = WritePayload.json(e, clock.zone());
        } catch (WritePayload.NotWritable | RuntimeException ex) {
            return Result.fail(FetchOutcome.OK, DetailCode.REJECTED_400_422);
        }
        Result<WriteAck> put = authed(Endpoint.IF40_UPDATE, "PUT", b -> api(b, "api", "v3", "treatments", e.id()), body,
                NsTime.ifUnmodifiedSince(expectedSrvModifiedMs), CallKind.WRITE, (code, text) -> {
                    if (code == 200 || code == 201 || code == 204) {
                        Double lastModified = lastModifiedOf(text);
                        return Result.ok(new WriteAck(e.id(),
                                lastModified == null ? Long.MIN_VALUE : Math.round(lastModified), false));
                    }
                    if (code == 412) {
                        return Result.fail(FetchOutcome.OK, DetailCode.PRECONDITION_412);
                    }
                    if (code == 404 || code == 410) {
                        return Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
                    }
                    return rejected(code);
                });
        if (!put.ok() || put.value().srvModifiedMs() != Long.MIN_VALUE) {
            return put;
        }
        // CP-00A R-40: no lastModified in the 200 body → one IF-39 read; if it fails → -1.
        Result<Entry> read = get(e.id());
        Long srv = read.ok() ? read.value().srvModifiedMs() : null;
        return Result.ok(new WriteAck(e.id(), srv == null ? -1L : srv, false));
    }

    @Override
    public Result<WriteAck> delete(String id) {
        if (id == null || id.isBlank()) {
            return Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
        }
        return authed(Endpoint.IF41_DELETE, "DELETE", b -> api(b, "api", "v3", "treatments", id), null, null, CallKind.WRITE,
                (code, text) -> {
            if (code == 200 || code == 204) {
                return Result.ok(new WriteAck(id, 0L, false));
            }
            if (code == 404 || code == 410) {
                return Result.fail(FetchOutcome.OK, DetailCode.GONE_404_410);
            }
            return rejected(code);
        });
    }

    // ------------------------------------------------------------------------------------------- core

    /**
     * One authenticated call with the C-2 JWT rules: JWT ensured first; a 401 on a read → exactly one IF-30
     * refresh and one repeat, a second 401 → {@code TOKEN_REJECTED}; a 401 on a write → {@code TOKEN_REJECTED}
     * without repeat (C-4: writes are never retried automatically); 403 → {@code TOKEN_REJECTED} /
     * {@code FORBIDDEN_403}. The JWT is discarded on every {@code TOKEN_REJECTED} (SR-03).
     *
     * <p>Debug builds only: the JSON parse + mapping time of the reply is logged as
     * {@code IF-<n> <name> <ms>ms mapped} (the HTTP exchange itself is logged by {@link NsHttp}).
     */
    private <T> Result<T> authed(Endpoint endpoint, String method, UrlBuilder url, String jsonBody,
                                 String ifUnmodifiedSince, CallKind kind, ReplyHandler<T> handler) {
        try {
            Connection c = connection;
            if (c == null) {
                return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
            }
            if (c.base == null) {
                return Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.CONNECT_FAILED);
            }
            Result<String> jwt = c.jwt.obtain(null);
            if (!jwt.ok()) {
                return Result.fail(jwt.outcome(), jwt.detailCode());
            }
            HttpCall call = new HttpCall(endpoint, method, url.build(c.base).build(), jsonBody, ifUnmodifiedSince,
                    kind.shortBudget, jwt.value(), c.origin);
            String used = jwt.value();
            HttpReply reply = http.execute(call);
            if (!reply.isFailure() && reply.code() == 401) {
                if (!kind.repeatAfter401) {
                    c.jwt.discard(used);
                    return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
                }
                Result<String> refreshed = c.jwt.obtain(used);
                if (!refreshed.ok()) {
                    return Result.fail(refreshed.outcome(), refreshed.detailCode());
                }
                used = refreshed.value();
                reply = http.execute(call.withJwt(used));
                if (!reply.isFailure() && reply.code() == 401) {
                    c.jwt.discard(used);
                    return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
                }
            }
            if (reply.isFailure()) {
                return ErrorClassifier.failure(reply);
            }
            if (reply.code() == 403) {
                c.jwt.discard(used);
                return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
            }
            Result<T> handled;
            long mapStart = System.nanoTime();
            try {
                handled = handler.handle(reply.code(), reply.body());
            } catch (Json.BadJson e) {
                return ErrorClassifier.nonJson();
            } finally {
                logger.debug(NsHttp.TIMING_TAG, NsHttp.timingLine(endpoint, mapStart, "mapped"));
            }
            return handled != null ? handled : ErrorClassifier.status(reply.code());
        } catch (RuntimeException | Error e) {
            return ErrorClassifier.unexpected();
        }
    }

    /** Builds the request URL from the configured base. */
    private interface UrlBuilder {
        HttpUrl.Builder build(HttpUrl base);
    }

    /**
     * IF-30: reads the token on demand (SR-02), builds the request, wipes the {@code char[]} (IF-61: the caller
     * wipes), fetches the JWT. No request is sent when the token source is empty ({@code TOKEN_MISSING}).
     */
    private Result<JwtHolder.Jwt> fetchJwt(Connection c) {
        Optional<char[]> token;
        try {
            token = c.tokens == null ? Optional.empty() : c.tokens.token();
        } catch (StorageException e) {
            return Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        }
        if (token == null || token.isEmpty()) {
            return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
        }
        char[] chars = token.get();
        HttpUrl url;
        try {
            if (chars.length == 0) {
                return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
            }
            url = api(c.base, "api", "v2", "authorization", "request").addPathSegment(new String(chars)).build();
        } finally {
            Arrays.fill(chars, '\0');
        }
        HttpReply reply = http.execute(HttpCall.get(Endpoint.IF30_JWT, url, c.origin));
        if (reply.isFailure()) {
            return ErrorClassifier.failure(reply);
        }
        if (reply.code() != 200) {
            return ErrorClassifier.status(reply.code());
        }
        try {
            JSONObject j = Json.object(reply.body());
            String jwt = Json.str(j, "token");
            if (jwt == null || jwt.isBlank()) {
                return ErrorClassifier.nonJson();
            }
            return Result.ok(new JwtHolder.Jwt(jwt, JwtExpiry.expMs(jwt, Json.numberOrNull(j, "exp")),
                    permissionGroups(j)));
        } catch (Json.BadJson e) {
            return ErrorClassifier.nonJson();
        }
    }

    /**
     * Reads IF-30 {@code permissionGroups}: an array of arrays of strings, one per role (BUG-15). A missing or
     * malformed value yields {@code null}; the JWT stays usable for data calls and only IF-32 fails
     * ({@code NON_JSON}).
     */
    private static List<List<String>> permissionGroups(JSONObject j) {
        if (!(Json.opt(j, "permissionGroups") instanceof JSONArray groups)) {
            return null;
        }
        List<List<String>> out = new ArrayList<>(groups.length());
        for (int i = 0; i < groups.length(); i++) {
            if (!(groups.opt(i) instanceof JSONArray group)) {
                return null;
            }
            List<String> perms = new ArrayList<>(group.length());
            for (int k = 0; k < group.length(); k++) {
                if (!(group.opt(k) instanceof String p)) {
                    return null;
                }
                perms.add(p);
            }
            out.add(List.copyOf(perms));
        }
        return List.copyOf(out);
    }

    private <T> Result<Page<T>> entriesCall(String type, UnaryOperator<HttpUrl.Builder> filter, int limit,
                                            ArrayMapper<Page<T>> mapper) {
        Endpoint endpoint = "mbg".equals(type) ? Endpoint.IF34_MBG : Endpoint.IF34_SGV;
        return authed(endpoint, "GET", b -> filter.apply(api(b, "api", "v3", "entries")
                        .addEncodedQueryParameter("type$eq", type))
                        .addEncodedQueryParameter("sort", "date")
                        .addEncodedQueryParameter("limit", Integer.toString(limit)),
                null, null, CallKind.READ, arrayHandler(mapper));
    }

    /**
     * IF-34 window filter. BUG-18: {@code $lt} first, {@code $gte} last. Nightscout ≤ 15.0.x builds the MongoDB
     * filter with {@code filter[field] = item}, so of two filters on {@code date} only the last survives; with the
     * old order the query was {@code date < to}, {@code sort=date}, {@code limit=1000} — the oldest 1000 documents
     * of the whole collection for every window, never complete. In this order such a server honours
     * {@code date >= from} and a server that merges both bounds honours the window; {@link #windowPage} trims
     * and decides completeness for both.
     */
    private static UnaryOperator<HttpUrl.Builder> window(Instant from, Instant to) {
        return b -> b.addEncodedQueryParameter("date$lt", Long.toString(to.toEpochMilli()))
                .addEncodedQueryParameter("date$gte", Long.toString(from.toEpochMilli()));
    }

    /** Event time of a raw document in epoch ms as the server filters and sorts it; {@code null} when unknown. */
    private interface DocTime {
        Long ms(JSONObject doc);
    }

    private static Long entryDateMs(JSONObject doc) {
        try {
            return Json.epochMs(doc, "date");
        } catch (Json.InvalidDocument e) {
            return null;
        }
    }

    private static Long createdAtMs(JSONObject doc) {
        Instant t = NsTime.parseIso(Json.str(doc, "created_at"));
        return t == null ? null : t.toEpochMilli();
    }

    /**
     * BUG-18 window page (ascending sort on the filtered field): documents outside {@code [from, to)} are dropped
     * before mapping, and mapped items outside it after mapping (a document without the filtered time field is
     * mapped from another field) — nothing outside the window may reach a set-difference replace.
     *
     * <p>The page is complete when the server returned fewer than {@code limit} documents, or (S-B F1) when the
     * raw page is verifiably sorted — every document has a known time, the times never decrease — and its last
     * document lies at or after {@code to}: then everything of the window precedes it. Any other full page (out of
     * order, mixed {@code created_at} formats, unknown times) is incomplete: upsert only, never a replace.
     *
     * <p>A document before {@code from} means the server ignored the lower bound: the page then stays incomplete
     * unless short, and the use cases stop the fetch (SlicedWindowFetcher guard). Debug line only — no event code
     * exists for this (Q-100; counts only, SR-07).
     */
    private <T> Result<Page<T>> windowPage(Endpoint endpoint, JSONArray array, Instant from, Instant to, int limit,
                                           DocTime time, Function<JSONArray, Mapped<T>> mapper,
                                           Function<T, Instant> timeOf) {
        long fromMs = from.toEpochMilli();
        long toMs = to.toEpochMilli();
        JSONArray inside = new JSONArray();
        int before = 0;
        boolean sorted = true;
        Long previous = null;
        Long last = null;
        for (int i = 0; i < array.length(); i++) {
            Object o = array.opt(i);
            Long ms = o instanceof JSONObject doc ? time.ms(doc) : null;
            if (ms == null || (previous != null && ms < previous)) {
                sorted = false;
            }
            if (ms != null) {
                previous = ms;
            }
            last = ms;
            if (ms != null && ms >= toMs) {
                continue;
            } else if (ms != null && ms < fromMs) {
                before++;
            } else {
                inside.put(o);   // unknown time: the mapper decides (M-8)
            }
        }
        if (before > 0) {
            logger.debug(WINDOW_TAG, endpoint.label() + " window-ignored before=" + before
                    + " of=" + array.length());
        }
        boolean reachedEnd = sorted && last != null && last >= toMs;
        List<T> items = new ArrayList<>();
        for (T item : mapper.apply(inside).items()) {
            Instant t = timeOf.apply(item);
            if (t == null || (!t.isBefore(from) && t.isBefore(to))) {
                items.add(item);
            }
        }
        return Result.ok(new Page<>(items, array.length() < limit || reachedEnd));
    }

    /** Maps a response array into a result. */
    private interface ArrayMapper<T> {
        Result<T> map(JSONArray array);
    }

    private static <T> ReplyHandler<T> arrayHandler(ArrayMapper<T> mapper) {
        return (code, body) -> code == 200 ? mapper.map(Json.resultArray(body)) : null;
    }

    /** C-8: {@code complete} only when the server returned fewer documents than the limit. */
    private static <T> Result<Page<T>> page(Mapped<T> mapped, int limit) {
        return Result.ok(new Page<>(mapped.items(), mapped.rawCount() < limit));
    }

    private <T> Mapped<T> log(NsCollection c, Mapped<T> mapped) {
        if (mapped.skipped() > 0) {
            logger.event(EventCode.MAPPING_SKIPPED, c.name() + ":" + mapped.skipped());
        }
        return mapped;
    }

    private static <T> Result<T> rejected(int code) {
        if (code == 400 || code == 422) {
            return Result.fail(FetchOutcome.OK, DetailCode.REJECTED_400_422);
        }
        return null;
    }

    private static Double lastModifiedOf(String body) {
        JSONObject j = objectOrNull(body);
        return j == null ? null : Json.numberOrNull(j, "lastModified");
    }

    private static JSONObject objectOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.object(body);
        } catch (Json.BadJson e) {
            return null;
        }
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    static String collectionName(NsCollection c) {
        return c.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses and normalises a base URL: http(s) only (cleartext is refused later by the connection specs, C-1),
     * query and fragment dropped, path kept (Nightscout may live under a path).
     */
    private static HttpUrl parseBase(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        HttpUrl url = HttpUrl.parse(baseUrl.trim());
        return url == null ? null : url.newBuilder().query(null).fragment(null).build();
    }

    /** Builds {@code base path + segments}; the base path's empty segments are dropped. */
    private static HttpUrl.Builder api(HttpUrl base, String... segments) {
        HttpUrl.Builder b = base.newBuilder().query(null).fragment(null).encodedPath("/");
        for (String s : base.pathSegments()) {
            if (!s.isEmpty()) {
                b.addPathSegment(s);
            }
        }
        for (String s : segments) {
            b.addPathSegment(s);
        }
        return b;
    }
}
