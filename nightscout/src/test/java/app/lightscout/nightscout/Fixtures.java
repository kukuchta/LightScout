package app.lightscout.nightscout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads the synthetic fixtures FX-01…FX-27 (CP-03 §9.1, SR-38/39). All values are invented: no real readings,
 * treatments, URLs, tokens or phone models.
 *
 * <p>FX-23 ({@code /api/v3/status}) is no longer read by the gateway (BUG-15, D-060). It is kept on purpose as
 * documented evidence of Nightscout's buggy {@code apiPermissions} report ({@code treatments:"r"} for a subject whose
 * {@code permissionGroups} grant treatments create/update/delete, EV-11); see
 * {@code ReadContractTest.bug15_check6PassesForLeastPrivilegeCudToken}.
 */
final class Fixtures {

    private Fixtures() {
    }

    /**
     * Returns a fixture file as text.
     *
     * @param name file name below {@code /fixtures/}
     * @return the text
     */
    static String text(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the {@code result} array of an envelope fixture.
     *
     * @param name file name
     * @return the array
     */
    static JSONArray resultArray(String name) {
        try {
            return new JSONObject(text(name)).getJSONArray("result");
        } catch (Exception e) {
            // org.json's JSONException is checked on the Android compile classpath, unchecked on the JVM one.
            throw new IllegalStateException("bad fixture " + name, e);
        }
    }

    /**
     * Wraps documents into a v3 search envelope.
     *
     * @param docs JSON documents
     * @return {@code {"status":200,"result":[…]}}
     */
    static String envelope(String... docs) {
        return "{\"status\":200,\"result\":[" + String.join(",", docs) + "]}";
    }

    /**
     * FX-25: a synthetic, unsigned JWT whose payload is {@code {"exp":<s>}}.
     *
     * @param expSeconds expiry in epoch seconds
     * @return {@code header.payload.sig}
     */
    static String jwt(long expSeconds) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"exp\":" + expSeconds + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".fakesig";
    }

    /**
     * FX-25: the IF-30 response body.
     *
     * @param jwt        the JWT
     * @param expSeconds expiry in epoch seconds
     * @return JSON text
     */
    static String jwtResponse(String jwt, long expSeconds) {
        return jwtResponse(jwt, expSeconds, "\"permissionGroups\":" + READABLE_GROUPS + ",");
    }

    /** EV-04 read token ({@code readable} role + an empty default role). */
    static final String READABLE_GROUPS = "[[\"*:*:read\"],[]]";
    /**
     * BUG-15 / EV-11 production evidence: {@code readable} + a custom role granting treatments create, update and
     * delete (+ an empty default role).
     */
    static final String READABLE_PLUS_TREATMENTS_CUD_GROUPS = "[[\"*:*:read\"],"
            + "[\"api:treatments:create\",\"api:treatments:update\",\"api:treatments:delete\"],[]]";
    /** {@code readable} + the built-in {@code careportal} role ({@code api:treatments:create} only, EV-04). */
    static final String READABLE_PLUS_CAREPORTAL_GROUPS = "[[\"*:*:read\"],[\"api:treatments:create\"],[]]";

    /**
     * FX-25 variant with an explicit {@code permissionGroups} member.
     *
     * @param jwt         the JWT
     * @param expSeconds  expiry in epoch seconds
     * @param groupsJson  JSON array of arrays of permission strings, or {@code null} to omit the member
     * @return JSON text
     */
    static String jwtResponseWithGroups(String jwt, long expSeconds, String groupsJson) {
        return jwtResponse(jwt, expSeconds, groupsJson == null ? "" : "\"permissionGroups\":" + groupsJson + ",");
    }

    private static String jwtResponse(String jwt, long expSeconds, String groupsMember) {
        return "{\"token\":\"" + jwt + "\",\"sub\":\"test-subject\"," + groupsMember
                + "\"iat\":" + (expSeconds - 8 * 3600) + ",\"exp\":" + expSeconds + "}";
    }
}
