package app.lightscout.nightscout;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.NsCollection;

/**
 * CMP-21 mapping of IF-37 history documents into {@link Change}s (CP-00A R-2), in server order
 * ({@code srvModified}).
 *
 * <ul>
 *   <li>{@code isValid: false} → a deletion with the document {@code identifier} and all payloads {@code null}.</li>
 *   <li>Otherwise the payload is mapped with the collection's mapper; a treatment that splits (M-2) yields one
 *       change per entry ({@code {id}#carbs}, {@code {id}#insulin}); a treatment that is not shown (M-7) yields a
 *       deletion for its plain id (IR-34, Q-39: CP-02 removes {@code X}, {@code X#carbs}, {@code X#insulin}); an
 *       entries document of another type yields none.</li>
 *   <li>{@code profile}: {@link Change} has no profile payload, so only deletions are reported (the latest profile
 *       is read with IF-36).</li>
 *   <li>A document without {@code identifier} or {@code srvModified}, or failing its mapper, is skipped and
 *       counted (M-8).</li>
 * </ul>
 */
final class HistoryMapper {

    private HistoryMapper() {
    }

    /**
     * Maps a history array.
     *
     * @param c     collection of the history call
     * @param array response array
     * @return changes
     */
    static Mapped<Change> map(NsCollection c, JSONArray array) {
        List<Change> out = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < array.length(); i++) {
            Object element = array.opt(i);
            if (!(element instanceof JSONObject doc)) {
                skipped++;
                continue;
            }
            try {
                out.addAll(map(c, doc));
            } catch (Json.InvalidDocument e) {
                skipped++;
            }
        }
        return new Mapped<>(out, array.length(), skipped);
    }

    private static List<Change> map(NsCollection c, JSONObject doc) throws Json.InvalidDocument {
        String id = Mappers.requireId(doc);
        Long srvModified = Json.epochMs(doc, "srvModified");
        if (srvModified == null) {
            throw new Json.InvalidDocument();
        }
        if (Boolean.FALSE.equals(Json.opt(doc, "isValid"))) {
            return List.of(new Change(c, id, srvModified, true, null, null, null));
        }
        switch (c) {
            case ENTRIES: {
                String type = Json.str(doc, "type");
                if ("sgv".equals(type)) {
                    return List.of(new Change(c, id, srvModified, false, EntriesMapper.reading(doc), null, null));
                }
                if ("mbg".equals(type)) {
                    return List.of(new Change(c, id, srvModified, false, null, EntriesMapper.meterBgEntry(doc), null));
                }
                return List.of();
            }
            case TREATMENTS: {
                List<Entry> entries = TreatmentsMapper.map(doc);
                if (entries.isEmpty()) {
                    // IR-34 / Q-39: a document that is (now) not shown removes every stored form of its id.
                    return List.of(new Change(c, id, srvModified, true, null, null, null));
                }
                List<Change> changes = new ArrayList<>(2);
                for (Entry e : entries) {
                    changes.add(new Change(c, e.id(), srvModified, false, null, e, null));
                }
                return changes;
            }
            case DEVICESTATUS:
                return List.of(new Change(c, id, srvModified, false, null, null, DeviceStatusMapper.map(doc)));
            case PROFILE:
            default:
                return List.of();
        }
    }
}
