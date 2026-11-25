package lightscout.buildlogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Names a release APK that the PO has already signed and writes its {@code .sha256} file
 * (CP-15 §8.6 release checklist step 4, CP-00A R-86).
 *
 * <p>The build itself only produces the <b>unsigned</b> release APK; signing is a manual PO step
 * (SR-39). This task first verifies (read-only, {@link ApkSignatureCheck}) that the input is signed with
 * APK Signature Scheme v2 + v3 and fails otherwise; it never signs and never reads a keystore. Usage:
 * {@code gradlew :app:lightscoutReleaseArtifact -Plightscout.signedApk=<path to signed apk>}.
 */
@DisableCachingByDefault(because = "Copies and hashes one local file")
public abstract class ReleaseArtifactTask extends DefaultTask {

    /**
     * Returns the APK signed by the PO.
     *
     * @return the signed APK input
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSignedApk();

    /**
     * Returns the SemVer {@code versionName} of the build.
     *
     * @return the version name
     */
    @Input
    public abstract Property<String> getVersionName();

    /**
     * Returns the directory that receives {@code lightscout-<v>-release.apk} and its {@code .sha256}.
     *
     * @return the output directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Verifies that the input is v2 + v3 signed, copies it under the release name and writes the checksum file.
     */
    @TaskAction
    public void writeArtifact() {
        Path signedApk = getSignedApk().get().getAsFile().toPath();
        List<String> signers;
        try {
            signers = ApkSignatureCheck.requireV2AndV3(signedApk);
        } catch (IllegalArgumentException e) {
            throw new GradleException(e.getMessage(), e);
        }
        for (String fingerprint : signers) {
            getLogger().lifecycle("Signer certificate SHA-256: {} (compare with the recorded fingerprint, SR-34)",
                    fingerprint);
        }
        String versionName = getVersionName().get();
        Path outputDir = getOutputDirectory().get().getAsFile().toPath();
        Path apk = outputDir.resolve(Versioning.releaseApkName(versionName));
        Path checksum = outputDir.resolve(Versioning.releaseChecksumName(versionName));
        try {
            Files.createDirectories(outputDir);
            Files.copy(signedApk, apk, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(checksum, Versioning.checksumLine(apk), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new GradleException("Could not write the release artifact " + apk.getFileName(), e);
        }
        getLogger().lifecycle("Release artifact: {} (+ {})", apk, checksum.getFileName());
    }
}
