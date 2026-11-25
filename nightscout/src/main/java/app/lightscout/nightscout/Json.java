package app.lightscout.nightscout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Tolerant reading of Nightscout JSON with the platform {@code org.json} (ADR-007). Typed getters treat
 * {@code null} / {@link JSONObject#NULL} as absent and never coerce strings to numbers (M-8 „wrong types”).
 */
final class Json {

    private Json() {
    }

    /**
     * Thrown when a body is not the expected JSON shape (→ {@code NON_JSON} / {@code NO_VERSION}).
     */
    static final class BadJson extends Exception {
        private static final long serialVersionUID = 1L;

        /** Creates the exception without a message (SR-07). */
        BadJson() {
            super();
        }
    }

    /**
     * Thrown when a single document cannot be mapped (M-8: skipped and counted).
     */
    static final class InvalidDocument extends Exception {
        private static final long serialVersionUID = 1L;

        /** Creates the exception without a message (SR-07). */
        InvalidDocument() {
            super();
        }
    }

    /**
     * Parses a body into a JSON value.
     *
     * @param body the body
     * @return a {@link JSONObject} or {@link JSONArray}
     * @throws BadJson if the body is not a JSON object or array
     */
    static Object parse(String body) throws BadJson {
        if (body == null) {
            throw new BadJson();
        }
        try {
            Object value = new JSONTokener(body).nextValue();
            if (value instanceof JSONObject || value instanceof JSONArray) {
                return value;
            }
        } catch (JSONException | RuntimeException e) {
            throw new BadJson();
        } catch (StackOverflowError e) {
            // Deeply nested JSON within the size cap (SR-29): the recursive parser runs out of stack.
            throw new BadJson();
        }
        throw new BadJson();
    }

    /**
     * Parses a body that must be a JSON object.
     *
     * @param body the body
     * @return the object
     * @throws BadJson if it is not a JSON object
     */
    static JSONObject object(String body) throws BadJson {
        Object value = parse(body);
        if (value instanceof JSONObject o) {
            return o;
        }
        throw new BadJson();
    }

    /**
     * Reads the {@code result} object of a v3 envelope {@code {status, result}} (C-6); unknown fields ignored.
     *
     * @param body the body
     * @return the result object
     * @throws BadJson if there is no {@code result} object
     */
    static JSONObject resultObject(String body) throws BadJson {
        JSONObject result = obj(object(body), "result");
        if (result == null) {
            throw new BadJson();
        }
        return result;
    }

    /**
     * Reads the {@code result} array of a v3 envelope (C-6). A bare array is accepted too.
     *
     * @param body the body
     * @return the array
     * @throws BadJson if there is no array
     */
    static JSONArray resultArray(String body) throws BadJson {
        Object value = parse(body);
        if (value instanceof JSONArray a) {
            return a;
        }
        Object result = ((JSONObject) value).opt("result");
        if (result instanceof JSONArray a) {
            return a;
        }
        throw new BadJson();
    }

    /**
     * Returns a member if it is present and not JSON null.
     *
     * @param o    object
     * @param name member name
     * @return the value or {@code null}
     */
    static Object opt(JSONObject o, String name) {
        Object v = o.opt(name);
        return v == null || v == JSONObject.NULL ? null : v;
    }

    /**
     * Returns a string member.
     *
     * @param o    object
     * @param name member name
     * @return the string, or {@code null} if absent or not a string
     */
    static String str(JSONObject o, String name) {
        Object v = opt(o, name);
        return v instanceof String s ? s : null;
    }

    /**
     * Returns an object member.
     *
     * @param o    object
     * @param name member name
     * @return the object or {@code null}
     */
    static JSONObject obj(JSONObject o, String name) {
        Object v = opt(o, name);
        return v instanceof JSONObject j ? j : null;
    }

    /**
     * Returns a finite numeric member.
     *
     * @param o    object
     * @param name member name
     * @return the number, {@code null} if absent
     * @throws InvalidDocument if present but not a finite number
     */
    static Double number(JSONObject o, String name) throws InvalidDocument {
        Object v = opt(o, name);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isFinite(d)) {
                return d;
            }
        }
        throw new InvalidDocument();
    }

    /**
     * Returns a finite numeric member, treating a wrong type as absent (used where several sources are tried).
     *
     * @param o    object, may be {@code null}
     * @param name member name
     * @return the number or {@code null}
     */
    static Double numberOrNull(JSONObject o, String name) {
        if (o == null) {
            return null;
        }
        Object v = opt(o, name);
        if (v instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        return null;
    }

    /**
     * Returns an epoch-ms member (an integral JSON number).
     *
     * @param o    object
     * @param name member name
     * @return the value, {@code null} if absent
     * @throws InvalidDocument if present but not a finite number
     */
    static Long epochMs(JSONObject o, String name) throws InvalidDocument {
        Double d = number(o, name);
        return d == null ? null : Math.round(d);
    }
}
