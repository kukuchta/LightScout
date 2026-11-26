package app.lightscout.domain.sound;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared validation of the text and number fields of sound entries. A stored value that fails here never reaches the
 * file system: the file name is a plain name, never a path (SR-07, path traversal).
 */
final class SoundFields {

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern BUNDLED_ID = Pattern.compile("[a-z0-9_]{1,24}");
    private static final Pattern FILE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final int MAX_URI_LENGTH = 512;

    private SoundFields() {
    }

    static boolean isKey(String text) {
        return text != null && KEY.matcher(text).matches();
    }

    static boolean isBundledId(String text) {
        return text != null && BUNDLED_ID.matcher(text).matches();
    }

    static String key(String text) {
        require(isKey(text), "Sound key must be 16 lower-case hex digits");
        return text;
    }

    static String fileName(String text) {
        require(text != null && FILE_NAME.matcher(text).matches(), "Sound file name must be a plain name");
        return text;
    }

    static String displayName(String text) {
        Objects.requireNonNull(text, "name");
        String stripped = text.strip();
        require(!stripped.isEmpty() && stripped.length() <= ImportLimits.MAX_NAME_LENGTH
                && stripped.chars().noneMatch(Character::isISOControl), "Sound name must be 1 to "
                + ImportLimits.MAX_NAME_LENGTH + " printable characters");
        return stripped;
    }

    static String sourceUri(String text) {
        Objects.requireNonNull(text, "uri");
        require(!text.isEmpty() && text.length() <= MAX_URI_LENGTH, "Sound source URI has a bad length");
        return text;
    }

    static long byteLength(long length) {
        require(length >= 1 && length <= ImportLimits.MAX_SOURCE_BYTES, "Sound size out of range");
        return length;
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
