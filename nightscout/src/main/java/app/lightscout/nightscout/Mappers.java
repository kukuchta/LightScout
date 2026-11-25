package app.lightscout.nightscout;

import org.json.JSONObject;

/**
 * Shared field rules of the CMP-21 mappers (M-5, M-8, CP-00A R-40 rounding).
 */
final class Mappers {

    private Mappers() {
    }

    /**
     * Returns the non-blank {@code identifier}.
     *
     * @param doc the document
     * @return the identifier
     * @throws Json.InvalidDocument if missing, blank or not a string
     */
    static String requireId(JSONObject doc) throws Json.InvalidDocument {
        String id = Json.str(doc, "identifier");
        if (id == null || id.isBlank()) {
            throw new Json.InvalidDocument();
        }
        return id;
    }

    /**
     * Returns a mandatory glucose-like value ≥ 1 (M-10: every {@code sgv ≥ 1} counts; no upper bound, so a bound
     * can never suppress a low — SR-29). Non-integral numbers are rounded.
     *
     * @param doc  the document
     * @param name member name
     * @return the value
     * @throws Json.InvalidDocument if missing, not a number, below 1 or beyond {@code int}
     */
    static int requirePositiveInt(JSONObject doc, String name) throws Json.InvalidDocument {
        Double d = Json.number(doc, name);
        if (d == null) {
            throw new Json.InvalidDocument();
        }
        long v = Math.round(d);
        if (v < 1 || v > Integer.MAX_VALUE) {
            throw new Json.InvalidDocument();
        }
        return (int) v;
    }

    /**
     * Returns an optional non-negative integer (carbs g, glucose mg/dL, duration min), rounded.
     *
     * @param doc  the document
     * @param name member name
     * @return the value, {@code null} if absent
     * @throws Json.InvalidDocument if present but not a number, negative or beyond {@code int}
     */
    static Integer optNonNegativeInt(JSONObject doc, String name) throws Json.InvalidDocument {
        Double d = Json.number(doc, name);
        if (d == null) {
            return null;
        }
        long v = Math.round(d);
        if (d < 0 || v > Integer.MAX_VALUE) {
            throw new Json.InvalidDocument();
        }
        return (int) v;
    }

    /**
     * Returns an optional non-negative decimal scaled to an integer unit (CP-00A R-40:
     * {@code Math.round(value × scale)}): insulin U → centi-U ({@code scale = 100}, M-5), U/h → milli-U/h
     * ({@code scale = 1000}).
     *
     * @param doc   the document
     * @param name  member name
     * @param scale 100 or 1000
     * @return the scaled value, {@code null} if absent
     * @throws Json.InvalidDocument if present but not a number, negative or beyond {@code int}
     */
    static Integer optScaled(JSONObject doc, String name, int scale) throws Json.InvalidDocument {
        Double d = Json.number(doc, name);
        if (d == null) {
            return null;
        }
        long v = Math.round(d * scale);
        if (d < 0 || v > Integer.MAX_VALUE) {
            throw new Json.InvalidDocument();
        }
        return (int) v;
    }

    /**
     * Optional non-negative decimal scaled to a long-valued integer unit (e.g. insulin U to milli-U).
     */
    static Long optScaledLong(JSONObject doc, String name, int scale) throws Json.InvalidDocument {
        Double d = Json.number(doc, name);
        if (d == null) {
            return null;
        }
        double scaled = d * scale;
        long v = Math.round(scaled);
        if (d < 0 || !Double.isFinite(scaled) || v < 0) {
            throw new Json.InvalidDocument();
        }
        return v;
    }

    /**
     * Tells whether an optional value is present and greater than zero.
     *
     * @param v the value
     * @return {@code v != null && v > 0}
     */
    static boolean positive(Integer v) {
        return v != null && v > 0;
    }
}
