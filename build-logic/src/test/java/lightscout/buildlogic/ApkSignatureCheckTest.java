package lightscout.buildlogic;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM tests of the release signature gate (IR-04). Only the rejecting paths are tested: a positive case would
 * need signing key material, which agents never create or read (SR-39).
 */
public class ApkSignatureCheckTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void unsignedApk_isRejected() throws IOException {
        Path apk = temp.newFile("unsigned.apk").toPath();
        try (OutputStream out = Files.newOutputStream(apk); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ApkSignatureCheck.requireV2AndV3(apk));
        assertTrue(e.getMessage(), e.getMessage().contains("unsigned.apk"));
    }

    @Test
    public void nonZipFile_isRejected() throws IOException {
        Path apk = temp.newFile("garbage.apk").toPath();
        Files.writeString(apk, "not an apk");

        assertThrows(IllegalArgumentException.class, () -> ApkSignatureCheck.requireV2AndV3(apk));
    }
}
