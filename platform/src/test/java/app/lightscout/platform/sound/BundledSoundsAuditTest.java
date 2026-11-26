package app.lightscout.platform.sound;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * DCR-51 S2: the sounds shipped inside the app. The classic tone moved from {@code :alarm} to {@code :platform} and
 * must stay byte for byte the file of release 0.1.0; every file in {@code res/raw} is a plain mono PCM 16-bit WAV
 * that {@code openRawResourceFd} and a looping {@code MediaPlayer} can use.
 */
public class BundledSoundsAuditTest {

    /** SHA-256 of {@code alarm_tone.wav} in release 0.1.0 (commit 0578e49). */
    private static final String CLASSIC_SHA256 = "ae75737e5629d8afcc0d603b4911ade49f67517bb7f4d3df7cd199eb7dea4e65";

    private static final Path RAW = Paths.get("src", "main", "res", "raw");
    private static final int HEADER_BYTES = 44;
    private static final Set<Integer> SAMPLE_RATES = Set.of(22_050, 44_100);

    @Test
    public void classicToneIsByteIdenticalToRelease010() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(RAW.resolve("alarm_tone.wav"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(CLASSIC_SHA256, hash);
    }

    @Test
    public void rawFolderHoldsOnlyTheClassicToneForNow() throws IOException {
        Set<String> names = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(RAW)) {
            for (Path file : files) {
                names.add(file.getFileName().toString());
            }
        }
        assertEquals(Set.of("alarm_tone.wav"), names);
    }

    @Test
    public void everyRawFileIsACanonicalMonoPcm16Wav() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(RAW, "*.wav")) {
            for (Path file : files) {
                assertCanonicalWav(file);
            }
        }
    }

    @Test
    public void classicResourceIsGenerated() {
        assertNotEquals(0, BundledSounds.classicRaw());
    }

    @Test
    public void noOtherSoundIsBundledYet() {
        assertTrue(BundledSounds.rawFor("chime").isEmpty());
        assertTrue(BundledSounds.rawFor("classic").isEmpty());
        assertTrue(BundledSounds.rawFor("").isEmpty());
        assertTrue(BundledSounds.rawFor(null).isEmpty());
    }

    private static void assertCanonicalWav(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        assertTrue(name + " is shorter than a header", bytes.length > HEADER_BYTES);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(name, "RIFF", text(bytes, 0));
        assertEquals(name + " RIFF size", bytes.length - 8, b.getInt(4));
        assertEquals(name, "WAVE", text(bytes, 8));
        assertEquals(name, "fmt ", text(bytes, 12));
        assertEquals(name + " fmt size", 16, b.getInt(16));
        assertEquals(name + " PCM", 1, b.getShort(20));
        assertEquals(name + " mono", 1, b.getShort(22));
        int rate = b.getInt(24);
        assertTrue(name + " rate " + rate, SAMPLE_RATES.contains(rate));
        assertEquals(name + " byte rate", rate * 2, b.getInt(28));
        assertEquals(name + " block align", 2, b.getShort(32));
        assertEquals(name + " 16 bit", 16, b.getShort(34));
        assertEquals(name, "data", text(bytes, 36));
        int dataBytes = b.getInt(40);
        assertEquals(name + " data size", bytes.length - HEADER_BYTES, dataBytes);
        double seconds = dataBytes / 2.0 / rate;
        assertTrue(name + " lasts " + seconds + " s", seconds >= 0.5 && seconds <= 30.0);
    }

    private static String text(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }
}
