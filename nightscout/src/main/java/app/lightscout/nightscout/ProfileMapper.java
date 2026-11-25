package app.lightscout.nightscout;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import app.lightscout.domain.model.BasalStep;
import app.lightscout.domain.model.Profile;

/**
 * CMP-21 mapping of {@code profile} (05 §4.3, CP-00A R-2): {@code store[defaultProfile].basal[]} →
 * {@link BasalStep}s ({@code timeAsSeconds}, else {@code time "HH:mm"}; {@code value} U/h × 1000 rounded — R-40),
 * {@code timezone} → zone. Start = {@code startDate} (ISO), else {@code created_at} (ISO), else {@code date} (ms).
 */
final class ProfileMapper {

    private static final int SECONDS_PER_DAY = 86_400;

    private ProfileMapper() {
    }

    /**
     * Maps one profile document.
     *
     * @param doc          the document
     * @param fallbackZone zone used when {@code timezone} is missing or unknown (the phone zone, IF-15)
     * @return the profile
     * @throws Json.InvalidDocument if id, start, default profile or a basal step is invalid (M-8)
     */
    static Profile map(JSONObject doc, ZoneId fallbackZone) throws Json.InvalidDocument {
        String id = Mappers.requireId(doc);
        Instant start = start(doc);
        String name = Json.str(doc, "defaultProfile");
        JSONObject store = Json.obj(doc, "store");
        JSONObject selected = name == null || store == null ? null : Json.obj(store, name);
        if (selected == null) {
            throw new Json.InvalidDocument();
        }
        Object basalValue = Json.opt(selected, "basal");
        if (!(basalValue instanceof JSONArray basal)) {
            throw new Json.InvalidDocument();
        }
        List<BasalStep> steps = new ArrayList<>(basal.length());
        for (int i = 0; i < basal.length(); i++) {
            if (!(basal.opt(i) instanceof JSONObject step)) {
                throw new Json.InvalidDocument();
            }
            steps.add(step(step));
        }
        return new Profile(id, start, zone(Json.str(selected, "timezone"), fallbackZone), steps);
    }

    private static Instant start(JSONObject doc) throws Json.InvalidDocument {
        Instant t = NsTime.parseIso(Json.str(doc, "startDate"));
        if (t == null) {
            t = NsTime.parseIso(Json.str(doc, "created_at"));
        }
        if (t == null) {
            Long date = Json.epochMs(doc, "date");
            t = date == null ? null : Instant.ofEpochMilli(date);
        }
        if (t == null) {
            throw new Json.InvalidDocument();
        }
        return t;
    }

    private static BasalStep step(JSONObject step) throws Json.InvalidDocument {
        Double seconds = Json.number(step, "timeAsSeconds");
        long start = seconds != null ? Math.round(seconds) : parseHhMm(Json.str(step, "time"));
        if (start < 0 || start >= SECONDS_PER_DAY) {
            throw new Json.InvalidDocument();
        }
        Integer rate = Mappers.optScaled(step, "value", 1000);
        if (rate == null) {
            throw new Json.InvalidDocument();
        }
        return new BasalStep((int) start, rate);
    }

    private static int parseHhMm(String time) throws Json.InvalidDocument {
        if (time == null) {
            throw new Json.InvalidDocument();
        }
        String[] parts = time.trim().split(":");
        if (parts.length != 2) {
            throw new Json.InvalidDocument();
        }
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                throw new Json.InvalidDocument();
            }
            return h * 3600 + m * 60;
        } catch (NumberFormatException e) {
            throw new Json.InvalidDocument();
        }
    }

    private static ZoneId zone(String timezone, ZoneId fallback) {
        if (timezone == null || timezone.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            return fallback;
        }
    }
}
