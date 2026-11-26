package app.lightscout.domain.sound;

import java.util.regex.Pattern;

/**
 * Cleans the name of a user file once, before it is stored or shown (DCR-51). The name is shown as plain text and is
 * never logged (SR-07).
 */
public final class SoundNames {

    /** Longest name that is kept, in characters. */
    static final int MAX_LENGTH = 40;

    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");
    private static final Pattern LAYOUT_SPACE = Pattern.compile("[\\t\\n\\r\\f]");
    /**
     * What is removed from a name: the set of {@code Character.isISOControl}, which {@link OwnSound} refuses (C0, DEL
     * and C1), plus zero-width characters, line and paragraph separators, text-direction marks and overrides, and the
     * byte order mark. A name with U+202E would show reversed in a list.
     */
    private static final Pattern OTHER_CONTROL = Pattern.compile(
            "[\\x00-\\x1F\\x7F-\\x9F\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u2069\\uFEFF]");
    private static final Pattern SPACE_RUN = Pattern.compile(" {2,}");

    private SoundNames() {
    }

    /**
     * Returns the name to store for a file: no extension, no control characters, single spaces, at most
     * {@value #MAX_LENGTH} characters. The result is always accepted by {@link OwnSound}.
     *
     * @param raw            the name the provider gave; {@code null} or an empty result is allowed
     * @param fallbackNumber number used for an empty name, "Sound N"
     * @return the cleaned name
     */
    public static String displayName(String raw, int fallbackNumber) {
        String text = raw == null ? "" : raw.strip();
        text = EXTENSION.matcher(text).replaceFirst("");
        text = LAYOUT_SPACE.matcher(text).replaceAll(" ");
        text = OTHER_CONTROL.matcher(text).replaceAll("");
        text = SPACE_RUN.matcher(text).replaceAll(" ").strip();
        if (text.length() > MAX_LENGTH) {
            int end = Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
            text = text.substring(0, end).strip();
        }
        return text.isEmpty() ? "Sound " + fallbackNumber : text;
    }
}
