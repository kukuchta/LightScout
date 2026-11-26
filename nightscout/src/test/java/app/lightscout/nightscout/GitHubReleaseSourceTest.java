package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import app.lightscout.domain.update.AppVersion;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

/** DCR-49: the update source reads only the release tag, sends nothing about the user and treats surprises as failures. */
public class GitHubReleaseSourceTest {

    private MockWebServer server;
    private GitHubReleaseSource source;

    @Before
    public void start() throws Exception {
        server = new MockWebServer();
        server.start();
        source = new GitHubReleaseSource(server.url("/repos/kukuchta/LightScout/releases/latest"), true);
    }

    @After
    public void stop() {
        server.close();
    }

    private void reply(int code, String body) {
        server.enqueue(new MockResponse.Builder().code(code).body(body).build());
    }

    private void assertFails() {
        try {
            source.latestStable();
            fail("expected IOException");
        } catch (IOException expected) {
            // the caller treats it as an unsuccessful check
        }
    }

    @Test
    public void readsTheStableVersionFromTheTag() throws Exception {
        reply(200, "{\"tag_name\":\"v0.2.0\",\"name\":\"LightScout 0.2.0\",\"prerelease\":false,\"draft\":false,"
                + "\"html_url\":\"https://evil.example/\",\"assets\":[]}");
        assertEquals(new AppVersion(0, 2, 0), source.latestStable());
    }

    @Test
    public void sendsAnAnonymousRequestToTheLatestReleaseEndpoint() throws Exception {
        reply(200, "{\"tag_name\":\"v1.0.0\"}");
        source.latestStable();
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/repos/kukuchta/LightScout/releases/latest", request.getTarget());
        assertEquals("application/vnd.github+json", request.getHeaders().get("Accept"));
        assertEquals("LightScout", request.getHeaders().get("User-Agent"));
        assertNull(request.getHeaders().get("Authorization"));
        assertNull(request.getHeaders().get("Cookie"));
    }

    @Test
    public void noReleaseYetIsNotAFailure() throws Exception {
        reply(404, "{\"message\":\"Not Found\"}");
        assertNull(source.latestStable());
    }

    @Test
    public void aPreReleaseOrDraftIsNotOffered() throws Exception {
        reply(200, "{\"tag_name\":\"v0.3.0\",\"prerelease\":true}");
        assertNull(source.latestStable());
        reply(200, "{\"tag_name\":\"v0.3.0\",\"draft\":true}");
        assertNull(source.latestStable());
    }

    @Test
    public void aServerErrorOrRateLimitIsAFailure() {
        reply(500, "oops");
        assertFails();
        reply(403, "{\"message\":\"API rate limit exceeded\"}");
        assertFails();
    }

    @Test
    public void anUnexpectedTagOrBodyIsAFailure() {
        reply(200, "{\"tag_name\":\"nightly-abc1234\"}");
        assertFails();
        reply(200, "{\"tag_name\":\"v0.2.0-rc1\"}");
        assertFails();
        reply(200, "{\"name\":\"no tag\"}");
        assertFails();
        reply(200, "<html>not json</html>");
        assertFails();
    }

    @Test
    public void aRedirectIsNotFollowed() {
        server.enqueue(new MockResponse.Builder().code(301).addHeader("Location", server.url("/elsewhere").toString())
                .build());
        assertFails();
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void aBodyAboveTheCapIsAFailure() {
        StringBuilder big = new StringBuilder("{\"tag_name\":\"v0.2.0\",\"body\":\"");
        while (big.length() < GitHubReleaseSource.MAX_BODY_BYTES + 1024) {
            big.append("x");
        }
        big.append("\"}");
        reply(200, big.toString());
        assertFails();
    }

    @Test
    public void constructingTheSourceDoesNotBuildTheHttpClient() throws Exception {
        // The container creates the source on the main thread when Home opens; building an OkHttpClient there
        // initialises TLS and is a fatal StrictMode violation in debug builds (the client is built on the first check).
        java.lang.reflect.Field field = GitHubReleaseSource.class.getDeclaredField("client");
        field.setAccessible(true);
        assertNull(field.get(new GitHubReleaseSource()));
    }

    @Test
    public void productionEndpointIsTheProjectsOwnHttpsReleaseApi() {
        assertTrue(GitHubReleaseSource.LATEST_RELEASE_URL.startsWith("https://api.github.com/repos/kukuchta/LightScout/"));
        assertTrue(GitHubReleaseSource.LATEST_RELEASE_URL.endsWith("/releases/latest"));
    }
}
