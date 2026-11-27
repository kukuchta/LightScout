package app.lightscout.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * DCR-51 S7: the words of the sound screens are pinned verbatim, no text is typed into code, and the settings module
 * reaches neither {@code android.media} nor {@code :storage} (plan section 8.2, audits).
 */
public class SoundScreensAuditTest {

    private static final Path MODULE = Paths.get("src", "main");
    private static final Path STRINGS = MODULE.resolve(Paths.get("res", "values", "strings.xml"));
    private static final Path JAVA = MODULE.resolve(Paths.get("java", "app", "lightscout", "feature", "settings"));
    private static final Pattern STRING = Pattern.compile("<string name=\"([^\"]+)\">([^<]*)</string>");
    private static final Pattern SOUND_KEY = Pattern.compile(
            "^(s13_|s14_|sound_|alarm_name_|s04_alarm_sounds).*");

    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("s04_alarm_sounds", "Alarm sounds"),
            Map.entry("s04_alarm_sounds_custom", "Custom: %1$s"),
            Map.entry("s04_alarm_sounds_custom_more", "Custom: %1$s +%2$d"),
            Map.entry("s13_title", "Alarm sounds"),
            Map.entry("sound_classic", "LightScout classic"),
            Map.entry("alarm_name_urgent_low", "Urgent low"),
            Map.entry("alarm_name_low", "Low"),
            Map.entry("alarm_name_no_data", "No data"),
            Map.entry("alarm_name_not_running", "Not running"),
            Map.entry("alarm_name_high", "High"),
            Map.entry("sound_play_cd", "Play %1$s"),
            Map.entry("sound_stop_cd", "Stop %1$s"),
            Map.entry("sound_remove_cd", "Remove %1$s"),
            Map.entry("s14_group_lightscout", "LightScout"),
            Map.entry("s14_group_phone", "Phone alarm sounds"),
            Map.entry("s14_group_own", "Your sounds"),
            Map.entry("s14_add_from_file", "+ Add from file"),
            Map.entry("s14_adding", "Adding…"),
            Map.entry("s14_copying", "Copying…"),
            Map.entry("s14_limit_reached", "Limit reached. Remove a sound to add another"),
            Map.entry("s14_removed", "Removed %1$s."),
            Map.entry("s14_removed_reassigned_one", "Removed %1$s. %2$s alarm now uses LightScout classic"),
            Map.entry("s14_removed_reassigned_many", "Removed %1$s. %2$s alarms now use LightScout classic"),
            Map.entry("s14_undo", "Undo"),
            Map.entry("s14_message_too_large", "That file is larger than 10 MB"),
            Map.entry("s14_message_cannot_read", "Could not read that sound. Try another one"),
            Map.entry("s14_message_not_playable", "That file cannot be played as an alarm sound"),
            Map.entry("sound_preview_failed", "Sound cannot be played")));

    private static Map<String, String> soundStrings() throws IOException {
        Map<String, String> found = new TreeMap<>();
        Matcher m = STRING.matcher(Files.readString(STRINGS));
        while (m.find()) {
            if (SOUND_KEY.matcher(m.group(1)).matches()) {
                found.put(m.group(1), m.group(2));
            }
        }
        return found;
    }

    @Test
    public void everySoundStringIsTheOneOfTheDesignAndNoOtherExists() throws IOException {
        assertEquals(EXPECTED, soundStrings());
    }

    @Test
    public void noScreenTypesTextIntoCode() throws IOException {
        Pattern literal = Pattern.compile("(setText|setTitle|setContentDescription|Snackbar\\.make)\\(.*\"[A-Za-z]");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(JAVA, "*.java")) {
            for (Path file : files) {
                String code = Files.readString(file);
                assertFalse(file + " types a UI text into code", literal.matcher(code).find());
            }
        }
    }

    @Test
    public void theSettingsModuleReachesNeitherAndroidMediaNorStorage() throws IOException {
        int scanned = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(JAVA, "*.java")) {
            for (Path file : files) {
                String code = Files.readString(file);
                assertFalse(file + " uses android.media", code.contains("android.media."));
                assertFalse(file + " uses :storage", code.contains("app.lightscout.storage."));
                scanned++;
            }
        }
        assertTrue(scanned > 5);
    }
}
