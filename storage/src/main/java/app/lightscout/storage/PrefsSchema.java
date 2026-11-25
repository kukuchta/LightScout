package app.lightscout.storage;

import android.content.SharedPreferences;

/**
 * Versioned key set of the preference stores ST-02…ST-05 (ADR-033, CP-04 §8.5).
 *
 * <p>Every write stamps the {@code schema} key. Version 1 is the first version, so no forward migration exists
 * yet; a later version adds its migration step here. A file written by an unknown <em>future</em> version keeps
 * all its values and its version number (the stamp never lowers it); keys this version does not know are left
 * untouched and keys it misses are read with their defaults. Nothing is ever dropped by a migration.
 */
final class PrefsSchema {

    /** Name of the version key (also one of the three allowed ST-05 keys, R-66). */
    static final String KEY = "schema";

    /** Current version of the key sets of this module. */
    static final int CURRENT = 1;

    private PrefsSchema() {
    }

    /**
     * Adds the version stamp to an edit unless the file already carries this or a newer version.
     *
     * @param prefs  the file being edited
     * @param editor the pending edit of that file
     */
    static void stamp(SharedPreferences prefs, SharedPreferences.Editor editor) {
        if (prefs.getInt(KEY, 0) < CURRENT) {
            editor.putInt(KEY, CURRENT);
        }
    }
}
