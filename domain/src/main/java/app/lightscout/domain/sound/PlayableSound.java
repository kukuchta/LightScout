package app.lightscout.domain.sound;

import java.util.Objects;

/**
 * What the alarm player should open for one alarm type (DCR-51): the classic tone, a bundled resource or a file in
 * app storage. Never a URI of another app.
 *
 * @param kind       {@link SoundKind#CLASSIC}, {@link SoundKind#BUNDLED} or {@link SoundKind#OWN} (any file)
 * @param name       bundled id for a bundled sound, absolute file path for a file, empty for the classic tone
 * @param byteLength size of the file in bytes, 0 when not a file
 */
public record PlayableSound(SoundKind kind, String name, long byteLength) {

    /** The classic tone, also the answer to every problem. */
    public static final PlayableSound CLASSIC = new PlayableSound(SoundKind.CLASSIC, "", 0L);

    /**
     * Checks the fields against the kind.
     *
     * @throws IllegalArgumentException when they do not fit
     */
    public PlayableSound {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        SoundFields.require(kind != SoundKind.PHONE, "A phone sound plays from its copy, as a file");
        SoundFields.require(kind == SoundKind.CLASSIC ? name.isEmpty() && byteLength == 0
                : !name.isEmpty() && (kind != SoundKind.BUNDLED || SoundFields.isBundledId(name)),
                "Bad playable sound");
        SoundFields.require(byteLength >= 0, "Bad playable sound size");
    }

    /**
     * Returns a bundled sound.
     *
     * @param id bundled id
     * @return the sound
     */
    public static PlayableSound bundled(String id) {
        return new PlayableSound(SoundKind.BUNDLED, id, 0L);
    }

    /**
     * Returns a sound stored as a file.
     *
     * @param absolutePath absolute path of the copy
     * @param byteLength   size of the copy in bytes
     * @return the sound
     */
    public static PlayableSound file(String absolutePath, long byteLength) {
        return new PlayableSound(SoundKind.OWN, absolutePath, byteLength);
    }
}
