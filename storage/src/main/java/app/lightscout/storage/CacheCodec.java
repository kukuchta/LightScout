package app.lightscout.storage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.model.BasalStep;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Trend;

/**
 * Column encodings of ST-01 (CP-04 §5.1, §8.1). Pure Java so that every mapping is JVM-tested.
 *
 * <ul>
 *   <li>{@code readings.direction}: the Nightscout direction name ({@code DoubleUp} … {@code DoubleDown});
 *       {@code NULL} = trend unknown (DDL comment).</li>
 *   <li>{@code entries.origin}: {@code treatments} | {@code entries-mbg} (DDL CHECK).</li>
 *   <li>{@code entries.type}: the {@link EntryType} constant name (DDL comment).</li>
 *   <li>{@code profile.basal_json}: {@code [{"timeAsSeconds":0,"value":0.1}]} with {@code value} in U/h;
 *       the domain keeps milli-U/h (CP-00A §0).</li>
 * </ul>
 */
final class CacheCodec {

    private static final String TIME_AS_SECONDS = "timeAsSeconds";
    private static final String VALUE = "value";
    private static final double MILLI = 1000.0;

    private CacheCodec() {
    }

    /**
     * @param trend domain trend
     * @return Nightscout direction name, or {@code null} for {@link Trend#UNKNOWN}
     */
    static String direction(Trend trend) {
        return switch (trend) {
            case DOUBLE_UP -> "DoubleUp";
            case SINGLE_UP -> "SingleUp";
            case FORTY_FIVE_UP -> "FortyFiveUp";
            case FLAT -> "Flat";
            case FORTY_FIVE_DOWN -> "FortyFiveDown";
            case SINGLE_DOWN -> "SingleDown";
            case DOUBLE_DOWN -> "DoubleDown";
            case UNKNOWN -> null;
        };
    }

    /**
     * @param direction stored direction; may be null
     * @return the trend; {@code null} or any unknown name → {@link Trend#UNKNOWN}
     */
    static Trend trend(String direction) {
        if (direction == null) {
            return Trend.UNKNOWN;
        }
        return switch (direction) {
            case "DoubleUp" -> Trend.DOUBLE_UP;
            case "SingleUp" -> Trend.SINGLE_UP;
            case "FortyFiveUp" -> Trend.FORTY_FIVE_UP;
            case "Flat" -> Trend.FLAT;
            case "FortyFiveDown" -> Trend.FORTY_FIVE_DOWN;
            case "SingleDown" -> Trend.SINGLE_DOWN;
            case "DoubleDown" -> Trend.DOUBLE_DOWN;
            default -> Trend.UNKNOWN;
        };
    }

    /**
     * @param origin entry origin
     * @return the column value allowed by the DDL CHECK
     */
    static String origin(EntryOrigin origin) {
        return switch (origin) {
            case TREATMENTS -> "treatments";
            case ENTRIES_MBG -> "entries-mbg";
        };
    }

    /**
     * @param value stored origin
     * @return the origin
     * @throws IllegalStateException for a value the DDL CHECK would have rejected
     */
    static EntryOrigin origin(String value) {
        if ("treatments".equals(value)) {
            return EntryOrigin.TREATMENTS;
        }
        if ("entries-mbg".equals(value)) {
            return EntryOrigin.ENTRIES_MBG;
        }
        throw new IllegalStateException("unknown origin");
    }

    /**
     * @param type entry type
     * @return the stored type name
     */
    static String entryType(EntryType type) {
        return type.name();
    }

    /**
     * @param value stored type name
     * @return the entry type
     * @throws IllegalStateException for an unknown name
     */
    static EntryType entryType(String value) {
        for (EntryType type : EntryType.values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalStateException("unknown entry type");
    }

    /**
     * @param zone profile zone; may be null
     * @return the zone id, or {@code null}
     */
    static String timezone(ZoneId zone) {
        return zone == null ? null : zone.getId();
    }

    /**
     * @param value stored zone id; may be null
     * @return the zone, or {@code null} when absent or not a valid zone id
     */
    static ZoneId timezone(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * Encodes a basal schedule as {@code basal_json}.
     *
     * @param basal schedule (rates in milli-U/h)
     * @return JSON array text
     * @throws IllegalStateException if the JSON cannot be built
     */
    static String basalJson(List<BasalStep> basal) {
        try {
            JSONArray array = new JSONArray();
            for (BasalStep step : basal) {
                JSONObject item = new JSONObject();
                item.put(TIME_AS_SECONDS, step.startSecondOfDay());
                item.put(VALUE, step.rateMilliPerHour() / MILLI);
                array.put(item);
            }
            return array.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("basal_json encode", e);
        }
    }

    /**
     * Decodes {@code basal_json}.
     *
     * @param json JSON array text
     * @return the schedule (rates rounded to milli-U/h)
     * @throws IllegalStateException if the text is not a valid basal array
     */
    static List<BasalStep> basal(String json) {
        try {
            JSONArray array = new JSONArray(json);
            List<BasalStep> steps = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                steps.add(new BasalStep(item.getInt(TIME_AS_SECONDS),
                        (int) Math.round(item.getDouble(VALUE) * MILLI)));
            }
            return steps;
        } catch (JSONException e) {
            throw new IllegalStateException("basal_json decode", e);
        }
    }
}
