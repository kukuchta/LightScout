package app.lightscout.nightscout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

/**
 * CMP-21 mapping of the {@code entries} collection (05 §4.1): {@code sgv} → {@link Reading}, {@code mbg} → meter BG
 * {@link Entry} (M-3).
 *
 * <ul>
 *   <li>Time = {@code date} (UTC ms); {@code utcOffset}, {@code sysTime}, {@code dateString} ignored (T-1, UG-08).</li>
 *   <li>Every {@code sgv ≥ 1} is a reading (M-10); future-dated readings are kept (M-9); no dedup here (DR-1).</li>
 *   <li>Numeric {@code trend}, {@code noise}, {@code filtered}, {@code unfiltered}, {@code delta} ignored (UG-13).</li>
 *   <li>An invalid document (wrong types, missing id / time, {@code sgv < 1}) is skipped and counted (M-8).</li>
 * </ul>
 */
final class EntriesMapper {

    private EntriesMapper() {
    }

    /**
     * Maps an array of {@code type = sgv} documents. Documents of another type are ignored (not counted).
     *
     * @param array response array
     * @return readings
     */
    static Mapped<Reading> readings(JSONArray array) {
        List<Reading> out = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (!(element instanceof JSONObject doc)) {
                skipped++;
                continue;
            }
            if (!"sgv".equals(Json.str(doc, "type"))) {
                continue;
            }
            try {
                out.add(reading(doc));
            } catch (Json.InvalidDocument e) {
                skipped++;
            }
        }
        return new Mapped<>(out, array.length(), skipped);
    }

    /**
     * Maps an array of {@code type = mbg} documents. Documents of another type are ignored (not counted).
     *
     * @param array response array
     * @return meter BG entries
     */
    static Mapped<Entry> meterBg(JSONArray array) {
        List<Entry> out = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (!(element instanceof JSONObject doc)) {
                skipped++;
                continue;
            }
            if (!"mbg".equals(Json.str(doc, "type"))) {
                continue;
            }
            try {
                out.add(meterBgEntry(doc));
            } catch (Json.InvalidDocument e) {
                skipped++;
            }
        }
        return new Mapped<>(out, array.length(), skipped);
    }

    /**
     * Maps one {@code sgv} document.
     *
     * @param doc the document
     * @return the reading
     * @throws Json.InvalidDocument if it cannot be mapped (M-8)
     */
    static Reading reading(JSONObject doc) throws Json.InvalidDocument {
        String id = Mappers.requireId(doc);
        Instant time = requireDate(doc);
        int sgv = Mappers.requirePositiveInt(doc, "sgv");
        return new Reading(id, time, sgv, trend(Json.str(doc, "direction")),
                TextSanitizer.clean(Json.str(doc, "device")));
    }

    /**
     * Maps one {@code mbg} document (M-3): {@code BG_CHECK}, origin {@code ENTRIES_MBG}, read-only.
     *
     * @param doc the document
     * @return the entry
     * @throws Json.InvalidDocument if it cannot be mapped (M-8)
     */
    static Entry meterBgEntry(JSONObject doc) throws Json.InvalidDocument {
        String id = Mappers.requireId(doc);
        Instant time = requireDate(doc);
        int mbg = Mappers.requirePositiveInt(doc, "mbg");
        return new Entry(id, EntryOrigin.ENTRIES_MBG, EntryType.BG_CHECK, time,
                mbg, null, null, null, null, null,
                null, null, null, false, null);
    }

    /**
     * Nightscout {@code direction} → {@link Trend} (05 §4.1, M3-34); empty, {@code NOT COMPUTABLE},
     * {@code RATE OUT OF RANGE}, {@code NONE} and anything else → {@link Trend#UNKNOWN}.
     *
     * @param direction the direction, may be {@code null}
     * @return the trend
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

    private static Instant requireDate(JSONObject doc) throws Json.InvalidDocument {
        Long date = Json.epochMs(doc, "date");
        if (date == null) {
            throw new Json.InvalidDocument();
        }
        return Instant.ofEpochMilli(date);
    }
}
