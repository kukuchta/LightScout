package lightscout.buildlogic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** JVM tests of the versioning and artifact-naming build logic (CP-15 §9 „Versioning”). */
public class VersioningTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void versionCode_1_0_0_is_10000() {
        assertEquals(10000, Versioning.versionCode(1, 0, 0));
        assertEquals(10000, Versioning.versionCode("1.0.0"));
    }

    @Test
    public void versionCode_1_2_3_is_10203() {
        assertEquals(10203, Versioning.versionCode(1, 2, 3));
        assertEquals(10203, Versioning.versionCode("1.2.3"));
    }

    @Test
    public void versionCode_isMonotonic() {
        assertTrue(Versioning.versionCode("1.2.99") < Versioning.versionCode("1.3.0"));
        assertTrue(Versioning.versionCode("1.99.99") < Versioning.versionCode("2.0.0"));
        assertEquals(100, Versioning.versionCode("0.1.0"));
    }

    @Test
    public void versionCode_rejectsOutOfRangeParts() {
        assertThrows(IllegalArgumentException.class, () -> Versioning.versionCode(1, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> Versioning.versionCode(1, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> Versioning.versionCode(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Versioning.versionCode(210_001, 0, 0));
    }

    @Test
    public void versionCode_rejectsNonSemVerNames() {
        for (String bad : new String[] {"1.2", "1.2.3.4", "1.2.3-rc1", "v1.2.3", "01.2.3", "", "1.2.x"}) {
            assertThrows(bad, IllegalArgumentException.class, () -> Versioning.versionCode(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> Versioning.versionCode((String) null));
    }

    @Test
    public void releaseArtifact_isNamedPerCp15() {
        assertEquals("lightscout-1.2.3-release.apk", Versioning.releaseApkName("1.2.3"));
        assertEquals("lightscout-1.2.3-release.apk.sha256", Versioning.releaseChecksumName("1.2.3"));
    }

    @Test
    public void checksumLine_matchesSha256sumFormatAndContent() throws IOException {
        Path apk = temp.newFile(Versioning.releaseApkName("1.2.3")).toPath();
        Files.write(apk, "abc".getBytes(StandardCharsets.US_ASCII));

        String line = Versioning.checksumLine(apk);

        // SHA-256("abc"), FIPS 180-2 test vector.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                + "  lightscout-1.2.3-release.apk\n", line);
    }
}
