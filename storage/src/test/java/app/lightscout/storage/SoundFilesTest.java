package app.lightscout.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** DCR-51: the sound directory. Files are named by a plain name only; a copy becomes visible by one rename. */
public class SoundFilesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private SoundFiles files() throws IOException {
        return new SoundFiles(new File(folder.getRoot(), "alarm_sounds"));
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> names(File dir) {
        Set<String> out = new TreeSet<>();
        String[] list = dir.list();
        if (list != null) {
            out.addAll(Set.of(list));
        }
        return out;
    }

    @Test
    public void constructingDoesNothingOnDisk() throws IOException {
        files();
        assertFalse(new File(folder.getRoot(), "alarm_sounds").exists());
    }

    @Test
    public void aTempFileIsCreatedEmptyInsideTheTempFolder() throws IOException {
        SoundFiles files = files();
        File temp = files.newTempFile();
        assertTrue(temp.isFile());
        assertEquals(0L, temp.length());
        assertEquals("tmp", temp.getParentFile().getName());
        assertTrue(temp.getName().endsWith(".part"));
    }

    @Test
    public void publishMovesTheBytesUnderThePlainName() throws IOException {
        SoundFiles files = files();
        File temp = files.newTempFile();
        write(temp, "sound bytes");
        String name = files.publish(temp, "own-0123456789abcdef");
        assertEquals("own-0123456789abcdef", name);
        assertFalse(temp.exists());
        assertArrayEquals("sound bytes".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(files.fileFor(name).toPath()));
    }

    @Test
    public void publishRefusesAnyNameThatIsNotPlain() throws IOException {
        SoundFiles files = files();
        for (String bad : new String[] {"", "../x", "a/b", "a\\b", ".", "..", "tmp/x.part", "Upper", "x y"}) {
            File temp = files.newTempFile();
            try {
                files.publish(temp, bad);
                fail("should refuse: " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue("temp stays for the caller to delete", temp.exists());
            }
        }
    }

    @Test
    public void publishNeverReplacesAFileThatAlreadyExists() throws IOException {
        SoundFiles files = files();
        File first = files.newTempFile();
        write(first, "live sound");
        files.publish(first, "own-a");
        File second = files.newTempFile();
        write(second, "new bytes");
        try {
            files.publish(second, "own-a");
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue("temp stays for the caller to delete", second.exists());
        }
        assertArrayEquals("live sound".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(files.fileFor("own-a").toPath()));
    }

    @Test
    public void fileForRefusesAPath() throws IOException {
        try {
            files().fileFor("../secret");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // plain names only
        }
    }

    @Test
    public void deleteRemovesTheNamedFilesAndIgnoresMissingOnes() throws IOException {
        SoundFiles files = files();
        File a = files.newTempFile();
        write(a, "a");
        files.publish(a, "own-a");
        File b = files.newTempFile();
        write(b, "b");
        files.publish(b, "own-b");
        files.delete(Set.of("own-a", "own-missing", "../escape"));
        assertEquals(Set.of("own-b", "tmp"), names(files.root()));
    }

    @Test
    public void sweepKeepsOnlyTheListedFilesAndEmptiesTheTempFolder() throws IOException {
        SoundFiles files = files();
        for (String name : new String[] {"own-a", "own-b", "phone-c"}) {
            File temp = files.newTempFile();
            write(temp, name);
            files.publish(temp, name);
        }
        File unfinished = files.newTempFile();
        write(unfinished, "half");
        files.sweep(Set.of("own-a", "phone-c"));
        assertEquals(Set.of("own-a", "phone-c", "tmp"), names(files.root()));
        assertTrue(names(new File(files.root(), "tmp")).isEmpty());
    }

    @Test
    public void sweepOnAMissingDirectoryDoesNothing() throws IOException {
        files().sweep(Set.of());
        assertFalse(new File(folder.getRoot(), "alarm_sounds").exists());
    }
}
