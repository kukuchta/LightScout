package app.lightscout.domain.sound;

/**
 * A phone alarm sound copied into app storage when it was chosen (DCR-51). The copy keeps playing if the phone later
 * changes or removes its own sounds.
 *
 * @param key        16 hex digits, random
 * @param sourceUri  URI the sound was copied from, used to find an existing copy
 * @param title      title of the sound as the phone names it, 1 to {@link ImportLimits#MAX_NAME_LENGTH} characters
 * @param fileName   plain name of the copy inside the sound directory, never a path
 * @param byteLength size of the copy in bytes, 1 to {@link ImportLimits#MAX_SOURCE_BYTES}
 */
public record PhoneCopy(String key, String sourceUri, String title, String fileName, long byteLength) {

    /**
     * Validates every field.
     *
     * @throws IllegalArgumentException when a field is out of range
     */
    public PhoneCopy {
        key = SoundFields.key(key);
        sourceUri = SoundFields.sourceUri(sourceUri);
        title = SoundFields.displayName(title);
        fileName = SoundFields.fileName(fileName);
        byteLength = SoundFields.byteLength(byteLength);
    }
}
