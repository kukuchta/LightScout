package app.lightscout.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static checks of the {@code :storage} main sources against the CP-04 §12 „Do not” list and SR-30 / SR-07:
 * SQL text only from constants, {@code commit()} not {@code apply()}, no forbidden key parameters, no platform
 * logging, no wall clock.
 */
public class SourceRulesTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "app", "lightscout", "storage");
    private static final Pattern SQL_CALL = Pattern.compile("\\b(rawQuery|execSQL|compileStatement)\\(\\s*([^,)]+)");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");

    private static List<String> sources() throws IOException {
        assertTrue("run from the module directory", Files.isDirectory(MAIN));
        List<String> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(MAIN)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                result.add(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    @Test
    public void sqlTextComesOnlyFromConstantsOrTheSchema() throws IOException {
        int calls = 0;
        for (String source : sources()) {
            Matcher m = SQL_CALL.matcher(source);
            while (m.find()) {
                calls++;
                String arg = m.group(2).trim();
                boolean allowed = CONSTANT.matcher(arg).matches()
                        || arg.startsWith("CacheSchema.dropStatement(")
                        || arg.equals("statement");
                assertTrue("SQL argument must be a constant: " + arg, allowed);
            }
        }
        assertTrue(calls > 20);
    }

    /**
     * IR-15 / SR-30 / M-12: every SharedPreferences key is a constant. In files that use SharedPreferences,
     * the key argument of every get / put / remove / contains call must be an UPPER_CASE constant (optionally
     * class-qualified) or one of the listed constant-returning indirections, and never contain {@code +}.
     */
    @Test
    public void preferenceKeysAreConstantsNeverConcatenated() throws IOException {
        Pattern call = Pattern.compile("(\\b\\w+)?\\s*\\.(getString|getInt|getLong|getBoolean|getFloat|getStringSet"
                + "|putString|putInt|putLong|putBoolean|putFloat|putStringSet|remove|contains)\\(\\s*([^,)]+)");
        Pattern constant = Pattern.compile("([A-Z]\\w*\\.)?[A-Z][A-Z0-9_]*");
        // constant-returning indirections: PrefsAppFlagsStore helpers receive only its key constants;
        // PrefsReminderStateStore.keyFor() switches to KEY_SITE / KEY_SENSOR.
        List<String> indirections = List.of("key", "keyFor(");
        int checked = 0;
        for (String source : sources()) {
            if (!source.contains("import android.content.SharedPreferences;")) {
                continue;
            }
            assertFalse("no key prefixes", source.contains("_PREFIX"));
            Matcher m = call.matcher(source);
            while (m.find()) {
                if ("set".equals(m.group(1))) {
                    continue;   // JSONObject of PrefsNotifiedAlertStore, not a preference
                }
                String arg = m.group(3).trim();
                checked++;
                assertFalse("concatenated preference key: " + arg, arg.contains("+"));
                boolean allowed = constant.matcher(arg).matches()
                        || indirections.stream().anyMatch(i -> i.endsWith("(") ? arg.startsWith(i) : arg.equals(i));
                assertTrue("preference key must be a constant: " + arg, allowed);
            }
        }
        assertTrue(checked > 30);
    }

    @Test
    public void preferencesAreNeverWrittenWithApply() throws IOException {
        for (String source : sources()) {
            assertFalse(source.contains(".apply()"));
        }
    }

    @Test
    public void keyIsNotAuthBoundNotUnlockedDeviceBoundNoStrongBoxNoOwnIv() throws IOException {
        for (String source : sources()) {
            assertFalse(source.contains(".setUnlockedDeviceRequired("));
            assertFalse(source.contains(".setIsStrongBoxBacked("));
            assertFalse(source.contains("setUserAuthenticationRequired(true)"));
            assertFalse(source.contains("setRandomizedEncryptionRequired(false)"));
            assertFalse(source.contains("IvParameterSpec"));
        }
    }

    @Test
    public void noPlatformLoggingNoWallClockNoExternalStorageNoWorldModes() throws IOException {
        for (String source : sources()) {
            assertFalse(source.contains("android.util.Log"));
            assertFalse(source.contains("System.out"));
            assertFalse(source.contains("System.currentTimeMillis"));
            assertFalse(source.contains("getExternal"));
            assertFalse(source.contains("MODE_WORLD"));
            assertFalse(source.contains(".apply("));
        }
    }
}
