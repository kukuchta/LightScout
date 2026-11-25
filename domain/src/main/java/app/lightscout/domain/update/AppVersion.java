package app.lightscout.domain.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stable LightScout version {@code MAJOR.MINOR.PATCH} (DCR-49). Pre-release names such as the nightly
 * {@code 0.1.0-nightly.20261125.abc1234} are not stable versions: {@link #parseStable} refuses them, so a nightly or
 * debug build never compares itself with a release.
 *
 * @param major major number
 * @param minor minor number
 * @param patch patch number
 */
public record AppVersion(int major, int minor, int patch) implements Comparable<AppVersion> {

    private static final Pattern STABLE = Pattern.compile("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})");

    /**
     * Parses a plain {@code MAJOR.MINOR.PATCH} text.
     *
     * @param text version text; {@code null} or anything else than three numbers gives empty
     * @return the version, or empty
     */
    public static Optional<AppVersion> parseStable(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = STABLE.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new AppVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))));
    }

    @Override
    public int compareTo(AppVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
