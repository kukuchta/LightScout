package app.lightscout.nightscout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import app.lightscout.domain.port.UpdateSource;
import app.lightscout.domain.update.AppVersion;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * {@link UpdateSource} over the public GitHub API (DCR-49): the newest published release of the project, which is the
 * newest <em>stable</em> one because GitHub's "latest release" skips pre-releases and drafts (the nightlies are
 * pre-releases).
 *
 * <p>Privacy and safety: one anonymous {@code GET} over https only (TLS 1.2+, no cleartext, no redirects), the same
 * request for every user: no identifier, no token, no app version, no cookie. The answer is capped at 512 KB and only
 * the release tag is read; the page the user is sent to is built from the parsed version
 * ({@code UpdateOffer.pageUrl()}), never taken from the reply. Blocking; call off the main thread.
 */
public final class GitHubReleaseSource implements UpdateSource {

    /** The newest published (non-pre-release, non-draft) release of the project. */
    static final String LATEST_RELEASE_URL = "https://api.github.com/repos/kukuchta/LightScout/releases/latest";
    /** Answer size cap. */
    static final long MAX_BODY_BYTES = 512L * 1024;
    /** Whole-call budget of the daily check. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpUrl url;
    private final OkHttpClient client;

    /** Creates the production source: https only, GitHub API. */
    public GitHubReleaseSource() {
        this(HttpUrl.get(LATEST_RELEASE_URL), false);
    }

    /**
     * Creates a source for a given URL.
     *
     * @param url            the "latest release" endpoint
     * @param allowCleartext {@code true} only for the JVM test client on plain-http mockwebserver
     */
    GitHubReleaseSource(HttpUrl url, boolean allowCleartext) {
        this.url = url;
        List<ConnectionSpec> specs = allowCleartext
                ? List.of(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                : List.of(ConnectionSpec.MODERN_TLS);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(CALL_TIMEOUT)
                .callTimeout(CALL_TIMEOUT)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionSpecs(specs)
                .build();
    }

    @Override
    public AppVersion latestStable() throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "LightScout")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            if (code == 404) {
                return null;   // no release published yet
            }
            if (code != 200) {
                throw new IOException("GitHub answered " + code);
            }
            return parse(response.peekBody(MAX_BODY_BYTES).string());
        }
    }

    private static AppVersion parse(String body) throws IOException {
        try {
            JSONObject release = new JSONObject(body);
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                return null;
            }
            String tag = release.optString("tag_name", "");
            String number = tag.startsWith("v") ? tag.substring(1) : tag;
            return AppVersion.parseStable(number)
                    .orElseThrow(() -> new IOException("Unexpected release tag"));
        } catch (JSONException e) {
            throw new IOException("Unexpected reply", e);
        }
    }
}
