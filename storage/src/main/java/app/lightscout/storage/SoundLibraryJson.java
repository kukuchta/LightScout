package app.lightscout.storage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.AlarmSoundChoice;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.Removal;
import app.lightscout.domain.sound.SoundLibrary;

/**
 * JSON form of the sound library ENT-14 (DCR-51): own sounds, phone copies and the removal that can still be undone,
 * as one preference value so a {@code commit()} writes it atomically. The choice per alarm type is stored apart, one
 * key each.
 *
 * <p>Reading never throws: text that is not JSON reads as an empty library, an entry that fails validation is
 * skipped, unknown keys and unknown alarm names are ignored (ADR-033). {@link SoundLibrary#of} then repairs the
 * choices that point to an entry that is gone, so an alarm at worst plays the classic tone.
 */
final class SoundLibraryJson {

    private static final String OWN = "own";
    private static final String PHONE = "phone";
    private static final String PENDING = "pending";
    private static final String REASSIGNED = "reassigned";
    private static final String KEY = "key";
    private static final String NAME = "name";
    private static final String FILE = "file";
    private static final String SIZE = "size";
    private static final String URI = "uri";

    private SoundLibraryJson() {
    }

    /**
     * Writes the own sounds, the phone copies and the pending removal.
     *
     * @param library the library
     * @return the JSON text
     */
    static String write(SoundLibrary library) {
        try {
            JSONObject root = new JSONObject();
            JSONArray own = new JSONArray();
            for (OwnSound sound : library.ownSounds()) {
                own.put(ownJson(sound));
            }
            root.put(OWN, own);
            JSONArray phone = new JSONArray();
            for (PhoneCopy copy : library.phoneCopies()) {
                phone.put(new JSONObject().put(KEY, copy.key()).put(URI, copy.sourceUri()).put(NAME, copy.title())
                        .put(FILE, copy.fileName()).put(SIZE, copy.byteLength()));
            }
            root.put(PHONE, phone);
            if (library.pendingRemoval().isPresent()) {
                Removal removal = library.pendingRemoval().get();
                JSONArray reassigned = new JSONArray();
                for (AlarmType type : removal.reassigned()) {
                    reassigned.put(type.name());
                }
                root.put(PENDING, new JSONObject().put(OWN, ownJson(removal.sound())).put(REASSIGNED, reassigned));
            }
            return root.toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot write the sound library", e);
        }
    }

    /**
     * Reads a stored library and repairs it against the stored choice.
     *
     * @param json   stored text; {@code null} or damaged text reads as no sounds
     * @param choice the choice read from its own keys
     * @return the repaired library
     */
    static SoundLibrary read(String json, AlarmSoundChoice choice) {
        if (json == null || json.isEmpty()) {
            return SoundLibrary.of(choice, List.of(), List.of(), null);
        }
        try {
            JSONObject root = new JSONObject(json);
            return SoundLibrary.of(choice, readOwn(root.optJSONArray(OWN)), readPhone(root.optJSONArray(PHONE)),
                    readPending(root.optJSONObject(PENDING)));
        } catch (JSONException e) {
            return SoundLibrary.of(choice, List.of(), List.of(), null);
        }
    }

    private static JSONObject ownJson(OwnSound sound) throws JSONException {
        return new JSONObject().put(KEY, sound.key()).put(NAME, sound.displayName()).put(FILE, sound.fileName())
                .put(SIZE, sound.byteLength());
    }

    private static List<OwnSound> readOwn(JSONArray array) {
        List<OwnSound> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            OwnSound sound = parseOwn(array.optJSONObject(i));
            if (sound != null) {
                out.add(sound);
            }
        }
        return out;
    }

    private static OwnSound parseOwn(JSONObject o) {
        if (o == null) {
            return null;
        }
        try {
            return new OwnSound(o.getString(KEY), o.getString(NAME), o.getString(FILE), o.getLong(SIZE));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    private static List<PhoneCopy> readPhone(JSONArray array) {
        List<PhoneCopy> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) {
                continue;
            }
            try {
                out.add(new PhoneCopy(o.getString(KEY), o.getString(URI), o.getString(NAME), o.getString(FILE),
                        o.getLong(SIZE)));
            } catch (JSONException | IllegalArgumentException e) {
                // an entry that fails validation is skipped; the choice that used it falls back to classic
            }
        }
        return out;
    }

    private static Removal readPending(JSONObject pending) {
        if (pending == null) {
            return null;
        }
        OwnSound sound = parseOwn(pending.optJSONObject(OWN));
        if (sound == null) {
            return null;
        }
        List<AlarmType> reassigned = new ArrayList<>();
        JSONArray names = pending.optJSONArray(REASSIGNED);
        for (int i = 0; names != null && i < names.length(); i++) {
            try {
                reassigned.add(AlarmType.valueOf(names.optString(i)));
            } catch (IllegalArgumentException e) {
                // an alarm name this version does not know is ignored
            }
        }
        return new Removal(sound, reassigned);
    }
}
