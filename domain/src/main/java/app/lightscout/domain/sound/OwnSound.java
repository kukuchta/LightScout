package app.lightscout.domain.sound;

/**
 * A sound file the user added (DCR-51), copied as it is into app storage.
 *
 * @param key         16 hex digits, random, never made from the name
 * @param displayName the user's file name, stripped, 1 to {@link ImportLimits#MAX_NAME_LENGTH} printable characters
 * @param fileName    plain name of the copy inside the sound directory, never a path
 * @param byteLength  size of the copy in bytes, 1 to {@link ImportLimits#MAX_SOURCE_BYTES}
 */
public record OwnSound(String key, String displayName, String fileName, long byteLength) {

    /**
     * Validates every field.
     *
     * @throws IllegalArgumentException when a field is out of range
     */
    public OwnSound {
        key = SoundFields.key(key);
        displayName = SoundFields.displayName(displayName);
        fileName = SoundFields.fileName(fileName);
        byteLength = SoundFields.byteLength(byteLength);
    }
}
