package app.lightscout.nightscout;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.json.JSONObject;

/**
 * Reads {@code exp} of a JWT for refresh timing only (C-2, SR-08, ADR-007 / N-14): Base64url-decode the payload and
 * read it with {@code org.json}. The signature is <b>not</b> verified (no custom cryptography, no JWT library).
 */
final class JwtExpiry {

    private JwtExpiry() {
    }

    /**
     * Returns the expiry in epoch ms.
     *
     * @param jwt         the JWT {@code header.payload.signature}
     * @param bodyExpSecs {@code exp} of the IF-30 response body (seconds), used if the payload has none
     * @return expiry in epoch ms, or {@link Long#MIN_VALUE} if unknown (then the JWT is refreshed on every use)
     */
    static long expMs(String jwt, Double bodyExpSecs) {
        Double exp = payloadExp(jwt);
        if (exp == null) {
            exp = bodyExpSecs;
        }
        if (exp == null || exp <= 0) {
            return Long.MIN_VALUE;
        }
        return Math.round(exp * 1000d);
    }

    private static Double payloadExp(String jwt) {
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            Object parsed = Json.parse(new String(payload, StandardCharsets.UTF_8));
            return parsed instanceof JSONObject o ? Json.numberOrNull(o, "exp") : null;
        } catch (IllegalArgumentException | Json.BadJson e) {
            return null;
        }
    }
}
