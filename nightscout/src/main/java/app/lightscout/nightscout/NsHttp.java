package app.lightscout.nightscout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.lightscout.domain.port.Connectivity;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Logger;
import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * CMP-20 transport: the OkHttp configuration and one HTTP exchange per call (C-1, C-3, C-7, C-10, C-11).
 *
 * <ul>
 *   <li>Timeouts connect 10 s · read 15 s · whole call 20 s (reads) / 15 s (writes) — C-3; one deadline per call
 *       across all redirect hops (IR-31).</li>
 *   <li>Transparent gzip (OkHttp default; no own {@code Accept-Encoding}) — C-7.</li>
 *   <li>{@code followRedirects(false)}, {@code followSslRedirects(false)}; 3xx handled here: followed only to the
 *       same origin over https, at most 3 hops; otherwise {@code REDIRECT_REFUSED} — C-10, SR-17, CP-00A R-40.</li>
 *   <li>{@code Authorization} is added by an interceptor only when the request URL has the configured origin — C-10.</li>
 *   <li>Decompressed body capped at 8 MB while streaming — C-11, SR-18.</li>
 *   <li>TLS: OkHttp default hostname verification and {@link ConnectionSpec#MODERN_TLS} (TLS 1.2+), no CLEARTEXT
 *       spec, no pinning, no custom TrustManager / HostnameVerifier, no logging interceptor — SR-15, SR-16.</li>
 *   <li>No silent connection retry ({@code retryOnConnectionFailure(false)}) — C-4.</li>
 *   <li>Debug builds only: one {@code Logger.debug} line per call, {@code IF-<n> <name> <ms>ms <result>
 *       [len=<chars>]} (call name, duration incl. redirect hops, status class or failure detail code, decoded
 *       body length) — no URL, host, token or body (SR-07).</li>
 * </ul>
 */
final class NsHttp {

    /**
     * Logcat tag of the debug-only per-call timing lines ({@code Logger.debug}: dropped by {@code PlatformLogger}
     * in release and stripped by R8 — app proguard rules, TST-13 H4).
     */
    static final String TIMING_TAG = "NsTiming";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long READ_CHUNK = 8192;

    private final HttpSettings settings;
    private final Connectivity connectivity;
    private final Logger logger;
    private final OkHttpClient readClient;
    private final OkHttpClient writeClient;

    /**
     * Creates the transport.
     *
     * @param settings     budget and policy
     * @param connectivity IF-19, separates {@code NO_INTERNET} from {@code NS_NOT_RESPONDING}
     * @param logger       IF-66
     */
    NsHttp(HttpSettings settings, Connectivity connectivity, Logger logger) {
        this.settings = settings;
        this.connectivity = connectivity;
        this.logger = logger;
        this.readClient = buildClient(settings);
        this.writeClient = readClient.newBuilder().callTimeout(settings.writeCallTimeout()).build();
    }

    /**
     * Builds the OkHttp client for reads and verification (the write client derives from it).
     *
     * @param s budget and policy
     * @return the client
     */
    static OkHttpClient buildClient(HttpSettings s) {
        List<ConnectionSpec> specs = s.allowCleartext()
                ? List.of(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                : List.of(ConnectionSpec.MODERN_TLS);
        return new OkHttpClient.Builder()
                .connectTimeout(s.connectTimeout())
                .readTimeout(s.readTimeout())
                .writeTimeout(s.readTimeout())
                .callTimeout(s.readCallTimeout())
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionSpecs(specs)
                .addInterceptor(new AuthInterceptor())
                .build();
    }

    /**
     * Returns the client used for a call class.
     *
     * @param write {@code true} for IF-38…IF-41
     * @return the client
     */
    OkHttpClient client(boolean write) {
        return write ? writeClient : readClient;
    }

    /**
     * Executes one call, following same-origin https redirects only. Never throws for transport problems.
     *
     * @param call the call
     * @return the reply (status + capped body for 2xx, or a classified failure)
     * @throws RuntimeException only for programming errors; the gateway catches everything
     */
    HttpReply execute(HttpCall call) {
        long start = System.nanoTime();
        HttpReply reply = exchange(call);
        logger.debug(TIMING_TAG, timingLine(call.endpoint(), start, resultClass(reply))
                + (reply.body() != null ? " len=" + reply.body().length() : ""));
        return reply;
    }

    /**
     * Builds a debug timing line {@code IF-<n> <name> <ms>ms <result>}: a constant call name, a duration and a
     * fixed result word — never a URL, host, token, value or body (SR-07).
     *
     * @param endpoint   call name
     * @param startNanos {@link System#nanoTime()} at the start
     * @param result     {@code 2xx}…{@code 5xx}, a {@link app.lightscout.domain.port.DetailCode} or {@code mapped}
     * @return the line
     */
    static String timingLine(Endpoint endpoint, long startNanos, String result) {
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return (endpoint == null ? "IF-0 unknown" : endpoint.label()) + " " + ms + "ms " + result;
    }

    /**
     * Returns the status class ({@code 2xx}, {@code 4xx}, …) or the failure detail code of a reply.
     *
     * @param reply the reply
     * @return the word
     */
    static String resultClass(HttpReply reply) {
        if (reply.isFailure()) {
            String d = reply.failureDetail();
            return d != null && d.matches("[A-Z0-9_]+") ? d : reply.failure().name();
        }
        return (reply.code() / 100) + "xx";
    }

    private HttpReply exchange(HttpCall call) {
        okhttp3.HttpUrl url = call.url();
        // C-3: one whole-call deadline across all redirect hops (IR-31); each hop gets the remaining budget.
        long budgetNanos = (call.write() ? settings.writeCallTimeout() : settings.readCallTimeout()).toNanos();
        long deadline = System.nanoTime() + budgetNanos;
        for (int hop = 0; ; hop++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return ErrorClassifier.transport(new InterruptedIOException(), connectivity.isOnline());
            }
            Request request = buildRequest(call, url);
            Call okCall = client(call.write()).newCall(request);
            okCall.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
            try (Response response = okCall.execute()) {
                int code = response.code();
                if (code >= 300 && code < 400) {
                    okhttp3.HttpUrl target = nextHop(call, url, response, hop);
                    if (target == null) {
                        logger.event(EventCode.REDIRECT_REFUSED);
                        return HttpReply.failed(FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
                    }
                    url = target;
                    continue;
                }
                String body = code >= 200 && code < 300 ? readCapped(response.body()) : null;
                return HttpReply.status(code, body);
            } catch (ResponseTooLargeException e) {
                logger.event(EventCode.RESPONSE_TOO_LARGE);
                return HttpReply.failed(FetchOutcome.NS_NOT_RESPONDING, DetailCode.RESPONSE_TOO_LARGE);
            } catch (IOException e) {
                return ErrorClassifier.transport(e, connectivity.isOnline());
            }
        }
    }

    private okhttp3.HttpUrl nextHop(HttpCall call, okhttp3.HttpUrl url, Response response, int hop) {
        if (hop >= settings.maxRedirects()) {
            return null;
        }
        int code = response.code();
        boolean methodPreserving = code == 307 || code == 308;
        boolean getRedirect = code == 301 || code == 302 || code == 303;
        // Writes are followed only when the method and body are preserved (307 / 308); 301/302/303 would turn a
        // write into a GET. 300, 304, 305, 306 are never followed (SR-17 „any other 3xx”).
        if (!methodPreserving && !(getRedirect && "GET".equals(call.method()))) {
            return null;
        }
        String location = response.header("Location");
        okhttp3.HttpUrl target = location == null ? null : url.resolve(location);
        return Origin.redirectAllowed(call.origin(), target, settings.allowCleartext()) ? target : null;
    }

    private static Request buildRequest(HttpCall call, okhttp3.HttpUrl url) {
        RequestBody body = call.jsonBody() == null ? null : RequestBody.create(call.jsonBody(), JSON);
        Request.Builder b = new Request.Builder()
                .url(url)
                .method(call.method(), body)
                .header("Accept", "application/json");
        if (call.ifUnmodifiedSince() != null) {
            b.header("If-Unmodified-Since", call.ifUnmodifiedSince());
        }
        if (call.jwt() != null) {
            b.tag(AuthTag.class, new AuthTag(call.jwt(), call.origin()));
        }
        return b.build();
    }

    /**
     * Reads the decoded body with the size cap; memory stays bounded by the cap plus one chunk (SR-18).
     *
     * @param body the response body (already gunzipped by OkHttp)
     * @return the UTF-8 text
     * @throws IOException                on transport errors
     * @throws ResponseTooLargeException  if the decoded body exceeds the cap
     */
    private String readCapped(ResponseBody body) throws IOException {
        long max = settings.maxBodyBytes();
        if (body.contentLength() > max) {
            throw new ResponseTooLargeException();
        }
        BufferedSource source = body.source();
        Buffer buffer = new Buffer();
        while (source.read(buffer, READ_CHUNK) != -1) {
            if (buffer.size() > max) {
                buffer.clear();
                throw new ResponseTooLargeException();
            }
        }
        return buffer.readUtf8();
    }

    /**
     * Tag that carries the JWT and the origin it may be sent to.
     *
     * @param jwt    the JWT (memory only)
     * @param origin the configured origin
     */
    record AuthTag(String jwt, Origin origin) {
        @Override
        public String toString() {
            return "AuthTag[masked]";
        }
    }

    /**
     * Adds {@code Authorization: Bearer <JWT>} only when the request goes to the configured origin; strips it
     * otherwise (C-10: {@code Authorization} is never sent to another host).
     */
    static final class AuthInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            AuthTag tag = request.tag(AuthTag.class);
            Request.Builder b = request.newBuilder().removeHeader("Authorization");
            if (tag != null && tag.origin().equals(Origin.of(request.url()))) {
                b.header("Authorization", "Bearer " + tag.jwt());
            }
            return chain.proceed(b.build());
        }
    }
}
