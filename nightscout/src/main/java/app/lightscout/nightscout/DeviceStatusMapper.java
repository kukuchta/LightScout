package app.lightscout.nightscout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import app.lightscout.domain.model.DeviceStatus;

/**
 * CMP-21 mapping of {@code devicestatus} (05 §4.3): time = {@code created_at}; IOB from the first present of
 * {@code openaps.iob.iob}, {@code loop.iob.iob}, {@code pump.iob.iob}, top-level {@code iob} (a number or an object
 * with {@code iob}); rounded to centi-U (CP-00A R-40). {@code uploader.battery} ignored (UG-09). A phone-only
 * document has {@code iobCenti = null}.
 */
final class DeviceStatusMapper {

    private DeviceStatusMapper() {
    }

    /**
     * Maps a devicestatus array.
     *
     * @param array response array
     * @return device statuses
     */
    static Mapped<DeviceStatus> map(JSONArray array) {
        List<DeviceStatus> out = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (!(element instanceof JSONObject doc)) {
                skipped++;
                continue;
            }
            try {
                out.add(map(doc));
            } catch (Json.InvalidDocument e) {
                skipped++;
            }
        }
        return new Mapped<>(out, array.length(), skipped);
    }

    /**
     * Maps one devicestatus document.
     *
     * @param doc the document
     * @return the device status
     * @throws Json.InvalidDocument if id or {@code created_at} is missing (M-8)
     */
    static DeviceStatus map(JSONObject doc) throws Json.InvalidDocument {
        String id = Mappers.requireId(doc);
        Instant time = NsTime.parseIso(Json.str(doc, "created_at"));
        if (time == null) {
            throw new Json.InvalidDocument();
        }
        Double iob = iob(doc);
        Integer iobCenti = null;
        if (iob != null) {
            long centi = Math.round(iob * 100);
            if (centi < Integer.MIN_VALUE || centi > Integer.MAX_VALUE) {
                throw new Json.InvalidDocument();
            }
            iobCenti = (int) centi;
        }
        return new DeviceStatus(id, time, iobCenti, TextSanitizer.clean(Json.str(doc, "device")));
    }

    private static Double iob(JSONObject doc) {
        for (String source : new String[] {"openaps", "loop", "pump"}) {
            JSONObject parent = Json.obj(doc, source);
            Double v = parent == null ? null : Json.numberOrNull(Json.obj(parent, "iob"), "iob");
            if (v != null) {
                return v;
            }
        }
        Object top = Json.opt(doc, "iob");
        if (top instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        if (top instanceof JSONObject o) {
            return Json.numberOrNull(o, "iob");
        }
        return null;
    }
}
