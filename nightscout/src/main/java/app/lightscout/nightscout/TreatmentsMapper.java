package app.lightscout.nightscout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;

/**
 * CMP-21 mapping of the {@code treatments} collection (05 §4.2, M-1…M-8, M-11, ADR-031, ADR-035, CP-00A R-40).
 *
 * <ul>
 *   <li>Time: {@code created_at} (ISO) else {@code date} (ms) else {@code timestamp} (ms) — first usable (UG-04).</li>
 *   <li>Known {@code eventType} first (M-1); missing / {@code "<none>"} / unknown → inferred from the values (M-2,
 *       R-40: glucose only → {@code BG_CHECK}); unknown without values → not shown (M-7).</li>
 *   <li>A document with both carbs and insulin ({@code Meal Bolus} or inferred) yields two read-only entries
 *       {@code {id}#carbs} and {@code {id}#insulin} (M-1, M-2).</li>
 *   <li>Ownership {@code own = (app == "LightScout")} (M-4); {@code srvModified} is used for own documents only.</li>
 *   <li>Insulin → centi-U for existing entry/write rules and milli-U for chart display; {@code absolute} /
 *       {@code rate} → milli-U/h with {@code Math.round} (M-5, R-40);
 *       {@code Temp Basal} with {@code percent} only → {@code basalRateMilli = null} (R-40).</li>
 *   <li>Pump alert: {@code eventType "Pump Alert"}, or {@code Announcement} with {@code notes} (UG-10).</li>
 *   <li>{@code enteredBy}, {@code notes} (→ {@code text}), {@code app}, {@code eventType} capped and stripped (M-11).</li>
 *   <li>Invalid documents are skipped and counted (M-8); {@code isValid: false} documents are not shown.</li>
 * </ul>
 */
final class TreatmentsMapper {

    /** {@code app} value of LightScout documents (05 §3, M-4). */
    static final String APP = "LightScout";

    private TreatmentsMapper() {
    }

    /**
     * Maps a treatments array.
     *
     * @param array response array
     * @return entries
     */
    static Mapped<Entry> map(JSONArray array) {
        List<Entry> out = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (!(element instanceof JSONObject doc)) {
                skipped++;
                continue;
            }
            if (Boolean.FALSE.equals(Json.opt(doc, "isValid"))) {
                continue;
            }
            try {
                out.addAll(map(doc));
            } catch (Json.InvalidDocument e) {
                skipped++;
            }
        }
        return new Mapped<>(out, array.length(), skipped);
    }

    /**
     * Maps one treatment document to zero, one or two entries.
     *
     * @param doc the document
     * @return the entries (empty when not shown — M-7)
     * @throws Json.InvalidDocument if it cannot be mapped (M-8)
     */
    static List<Entry> map(JSONObject doc) throws Json.InvalidDocument {
        Fields f = Fields.read(doc);
        String eventType = f.eventTypeRaw == null ? "" : f.eventTypeRaw.trim();
        switch (eventType) {
            case "BG Check":
                return List.of(f.single(EntryType.BG_CHECK));
            case "Carb Correction":
                return List.of(f.single(EntryType.CARBS));
            case "Correction Bolus":
            case "Bolus":
                return List.of(f.single(EntryType.BOLUS));
            case "Meal Bolus":
                return f.byValues(false);
            case "Site Change":
                return List.of(f.single(EntryType.SITE_CHANGE));
            case "Sensor Change":
            case "Sensor Start":
                return List.of(f.single(EntryType.SENSOR_CHANGE));
            case "Temp Basal":
                return List.of(f.tempBasal(doc));
            case "Pump Alert":
                return List.of(f.single(EntryType.PUMP_ALERT));
            case "Announcement":
                if (f.notes != null && !f.notes.isEmpty()) {
                    return List.of(f.single(EntryType.PUMP_ALERT));
                }
                return f.byValues(true);
            default:
                return f.byValues(true);
        }
    }

    /** Values read once per document. */
    private static final class Fields {
        private String id;
        private Instant time;
        private Integer glucose;
        private Integer carbs;
        private Integer insulinCenti;
        private Long insulinMilli;
        private String notes;
        private String enteredBy;
        private String app;
        private String eventTypeRaw;
        private boolean own;
        private Long srvModifiedMs;

        static Fields read(JSONObject doc) throws Json.InvalidDocument {
            Fields f = new Fields();
            f.id = Mappers.requireId(doc);
            f.time = time(doc);
            f.glucose = Mappers.optNonNegativeInt(doc, "glucose");
            f.carbs = Mappers.optNonNegativeInt(doc, "carbs");
            f.insulinCenti = Mappers.optScaled(doc, "insulin", 100);
            f.insulinMilli = Mappers.optScaledLong(doc, "insulin", 1000);
            f.notes = TextSanitizer.clean(Json.str(doc, "notes"));
            f.enteredBy = TextSanitizer.clean(Json.str(doc, "enteredBy"));
            String rawApp = Json.str(doc, "app");
            f.own = APP.equals(rawApp);
            f.app = TextSanitizer.clean(rawApp);
            f.eventTypeRaw = TextSanitizer.clean(Json.str(doc, "eventType"));
            f.srvModifiedMs = f.own ? Json.epochMs(doc, "srvModified") : null;
            return f;
        }

        private static Instant time(JSONObject doc) throws Json.InvalidDocument {
            Instant created = NsTime.parseIso(Json.str(doc, "created_at"));
            if (created != null) {
                return created;
            }
            Long date = Json.epochMs(doc, "date");
            if (date != null) {
                return Instant.ofEpochMilli(date);
            }
            Long timestamp = Json.epochMs(doc, "timestamp");
            if (timestamp != null) {
                return Instant.ofEpochMilli(timestamp);
            }
            throw new Json.InvalidDocument();
        }

        /** One entry of a known type; only the value field of that type is filled. */
        Entry single(EntryType type) {
            return new Entry(id, EntryOrigin.TREATMENTS, type, time,
                    type == EntryType.BG_CHECK ? glucose : null,
                    type == EntryType.CARBS ? carbs : null,
                    type == EntryType.BOLUS ? insulinCenti : null,
                    type == EntryType.BOLUS ? insulinMilli : null,
                    null, null, notes, enteredBy, app, eventTypeRaw, own, srvModifiedMs);
        }

        /**
         * M-2 (and the {@code Meal Bolus} split of M-1): carbs and insulin → two read-only entries; carbs only →
         * CARBS; insulin only → BOLUS; with {@code glucoseFallback} glucose only → BG_CHECK (R-40); else nothing.
         */
        List<Entry> byValues(boolean glucoseFallback) {
            boolean hasCarbs = Mappers.positive(carbs);
            boolean hasInsulin = insulinMilli != null && insulinMilli > 0;
            if (hasCarbs && hasInsulin) {
                List<Entry> two = new ArrayList<>(2);
                two.add(new Entry(id + "#carbs", EntryOrigin.TREATMENTS, EntryType.CARBS, time,
                        null, carbs, null, null, null, null, notes, enteredBy, app, eventTypeRaw, false, null));
                two.add(new Entry(id + "#insulin", EntryOrigin.TREATMENTS, EntryType.BOLUS, time,
                        null, null, insulinCenti, insulinMilli, null, null, notes, enteredBy, app, eventTypeRaw,
                        false, null));
                return two;
            }
            if (hasCarbs) {
                return List.of(single(EntryType.CARBS));
            }
            if (hasInsulin) {
                return List.of(single(EntryType.BOLUS));
            }
            if (glucoseFallback && Mappers.positive(glucose)) {
                return List.of(single(EntryType.BG_CHECK));
            }
            return List.of();
        }

        /** ADR-031: rate from {@code absolute}, else {@code rate}; {@code percent} only → rate {@code null}. */
        Entry tempBasal(JSONObject doc) throws Json.InvalidDocument {
            Integer rate = Mappers.optScaled(doc, "absolute", 1000);
            if (rate == null) {
                rate = Mappers.optScaled(doc, "rate", 1000);
            }
            Integer duration = Mappers.optNonNegativeInt(doc, "duration");
            return new Entry(id, EntryOrigin.TREATMENTS, EntryType.TEMP_BASAL, time,
                    null, null, null, null, rate, duration, notes, enteredBy, app, eventTypeRaw, own, srvModifiedMs);
        }
    }
}
