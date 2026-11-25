package app.lightscout.nightscout;

import java.time.ZoneId;
import java.math.BigDecimal;

import org.json.JSONException;
import org.json.JSONObject;

import app.lightscout.domain.model.Entry;

/**
 * Payload of documents written by LightScout (05 §3, ADR-038). Every create / update carries {@code identifier},
 * {@code date} (ms), {@code created_at} (same instant, ISO UTC), {@code utcOffset} (phone offset at the event,
 * minutes), {@code app} and {@code enteredBy} = {@code "LightScout"}; then the per-type {@code eventType} and value
 * fields. Single-type documents only (M-4); {@code PUMP_ALERT} and {@code TEMP_BASAL} are never written.
 */
final class WritePayload {

    private WritePayload() {
    }

    /**
     * Thrown when an entry cannot be written (read-only type or missing value); nothing is sent.
     */
    static final class NotWritable extends Exception {
        private static final long serialVersionUID = 1L;

        /** Creates the exception without a message (SR-07). */
        NotWritable() {
            super();
        }
    }

    /**
     * Builds the JSON body.
     *
     * @param e    the entry ({@code id} = client identifier, GIR-3)
     * @param zone phone zone (IF-15) for {@code utcOffset}
     * @return the JSON text
     * @throws NotWritable if the type is read-only or its value is missing
     */
    static String json(Entry e, ZoneId zone) throws NotWritable {
        if (e == null || e.id() == null || e.id().isBlank() || e.time() == null || e.type() == null) {
            throw new NotWritable();
        }
        try {
            JSONObject j = new JSONObject();
            j.put("identifier", e.id());
            j.put("date", e.time().toEpochMilli());
            j.put("created_at", NsTime.iso(e.time()));
            j.put("utcOffset", NsTime.utcOffsetMinutes(zone, e.time()));
            j.put("app", TreatmentsMapper.APP);
            j.put("enteredBy", TreatmentsMapper.APP);
            switch (e.type()) {
                case BG_CHECK:
                    requireValue(e.glucose());
                    j.put("eventType", "BG Check");
                    j.put("glucose", e.glucose().intValue());
                    j.put("glucoseType", "Finger");
                    j.put("units", "mg/dl");
                    break;
                case CARBS:
                    requireValue(e.carbs());
                    j.put("eventType", "Carb Correction");
                    j.put("carbs", e.carbs().intValue());
                    break;
                case BOLUS:
                    if (e.insulinMilli() == null) {
                        throw new NotWritable();
                    }
                    j.put("eventType", "Correction Bolus");
                    j.put("insulin", BigDecimal.valueOf(e.insulinMilli(), 3));
                    break;
                case SITE_CHANGE:
                    j.put("eventType", "Site Change");
                    break;
                case SENSOR_CHANGE:
                    j.put("eventType", "Sensor Change");
                    break;
                default:
                    throw new NotWritable();
            }
            return j.toString();
        } catch (JSONException ex) {
            throw new NotWritable();
        }
    }

    private static void requireValue(Integer value) throws NotWritable {
        if (value == null) {
            throw new NotWritable();
        }
    }
}
