package lightscout.buildlogic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioning and release artifact naming (CP-15 §8.6, CP-00A R-86).
 *
 * <p>{@code versionName} is SemVer {@code MAJOR.MINOR.PATCH};
 * {@code versionCode = MAJOR × 10000 + MINOR × 100 + PATCH}. The release artifact is
 * {@code lightscout-<versionName>-release.apk} with {@code lightscout-<versionName>-release.apk.sha256}.
 */
public final class Versioning {

    private static final Pattern SEMVER = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");

    /** Largest {@code versionCode} Android accepts. */
    private static final int MAX_VERSION_CODE = 2_100_000_000;

    private Versioning() {
    }

    /**
     * Computes the {@code versionCode} of a version.
     *
     * @param major MAJOR, {@code >= 0}
     * @param minor MINOR, {@code 0..99} (keeps the code monotonic)
     * @param patch PATCH, {@code 0..99}
     * @return {@code major × 10000 + minor × 100 + patch}
     * @throws IllegalArgumentException if a part is out of range
     */
    public static int versionCode(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || minor > 99 || patch < 0 || patch > 99) {
            throw new IllegalArgumentException(
                    "Version parts out of range (MAJOR >= 0, MINOR and PATCH 0..99): "
                            + major + "." + minor + "." + patch);
        }
        long code = major * 10_000L + minor * 100L + patch;
        if (code > MAX_VERSION_CODE) {
            throw new IllegalArgumentException("versionCode " + code + " exceeds " + MAX_VERSION_CODE);
        }
        return (int) code;
    }

    /**
     * Computes the {@code versionCode} of a SemVer {@code versionName}.
     *
     * @param versionName {@code MAJOR.MINOR.PATCH}
     * @return the version code
     * @throws IllegalArgumentException if the name is not plain SemVer or a part is out of range
     */
    public static int versionCode(String versionName) {
        Matcher matcher = SEMVER.matcher(versionName == null ? "" : versionName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("versionName must be MAJOR.MINOR.PATCH: " + versionName);
        }
        try {
            return versionCode(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("versionName part too large: " + versionName, e);
        }
    }

    /**
     * Returns the release APK file name.
     *
     * @param versionName SemVer version name (validated)
     * @return {@code lightscout-<versionName>-release.apk}
     */
    public static String releaseApkName(String versionName) {
        versionCode(versionName);
        return "lightscout-" + versionName + "-release.apk";
    }

    /**
     * Returns the checksum file name that belongs to the release APK.
     *
     * @param versionName SemVer version name (validated)
     * @return {@code lightscout-<versionName>-release.apk.sha256}
     */
    public static String releaseChecksumName(String versionName) {
        return releaseApkName(versionName) + ".sha256";
    }

    /**
     * Computes the lower-case hex SHA-256 of a file.
     *
     * @param file file to hash
     * @return 64 lower-case hex characters
     * @throws IOException if the file cannot be read
     */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JDK", e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Returns the content of the {@code .sha256} file in {@code sha256sum} format
     * ({@code <hex><space><space><file name><newline>}), so that {@code sha256sum -c} can verify it.
     *
     * @param apk the (signed) release APK, already named per {@link #releaseApkName(String)}
     * @return the checksum line
     * @throws IOException if the file cannot be read
     */
    public static String checksumLine(Path apk) throws IOException {
        return sha256Hex(apk) + "  " + apk.getFileName() + "\n";
    }
}
