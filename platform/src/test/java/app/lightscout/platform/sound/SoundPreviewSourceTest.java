package app.lightscout.platform.sound;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * DCR-51 S6, plan C-5 and M-3: the preview must never make a ringing alarm quieter and must never change a volume.
 * The Android calls cannot run on the JVM, so the rules are pinned in the source of the player.
 */
public class SoundPreviewSourceTest {

    private static final Path PLAYER = Paths.get("src", "main", "java", "app", "lightscout", "platform", "sound",
            "SoundPreviewPlayer.java");

    private static String code() throws IOException {
        StringBuilder out = new StringBuilder();
        boolean inBlockComment = false;
        for (String line : Files.readAllLines(PLAYER)) {
            String text = line.strip();
            if (inBlockComment) {
                inBlockComment = !text.contains("*/");
            } else if (text.startsWith("/*")) {
                inBlockComment = !text.contains("*/");
            } else if (!text.startsWith("//")) {
                out.append(line).append('\n');
            }
        }
        return out.toString().replaceAll("\\s+", "");   // a reformatted call must not slip past a substring check
    }

    @Test
    public void focusIsTransientOnly() throws IOException {
        String code = code();
        assertTrue(code.contains("AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)"));
        assertFalse("a permanent gain would lower a ringing alarm (IR-82)",
                code.contains("Builder(AudioManager.AUDIOFOCUS_GAIN)"));
        assertFalse(code.contains("AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK"));
    }

    @Test
    public void noVolumeAndNoDoNotDisturbCall() throws IOException {
        String code = code();
        assertFalse(code.contains("setStreamVolume"));
        assertFalse(code.contains("adjustStreamVolume"));
        assertFalse(code.contains("setInterruptionFilter"));
        assertFalse(code.contains("NotificationManager"));
        assertFalse(code.contains("setVolume"));
    }

    @Test
    public void playsAtAlarmUsageAndNeverLoops() throws IOException {
        String code = code();
        assertTrue(code.contains("USAGE_ALARM"));
        assertFalse(code.contains("setLooping(true)"));
    }

    @Test
    public void everyFocusChangeExceptAGainEndsThePreview() throws IOException {
        String code = code();
        assertTrue(code.contains("change!=AudioManager.AUDIOFOCUS_GAIN"));
        assertTrue(code.contains("controller.onFocusLost()"));
    }
}
