package app.lightscout.domain.sound;

/**
 * Limits of the sound library (DCR-51). A file is only copied as it is, so the limits are about size and count.
 */
public final class ImportLimits {

    /** Most own sounds the user can keep at once. */
    public static final int MAX_OWN_SOUNDS = 5;

    /** Largest file that is accepted, in bytes (10 MB). */
    public static final long MAX_SOURCE_BYTES = 10L * 1024 * 1024;

    /** Longest display name of a sound, in characters. */
    public static final int MAX_NAME_LENGTH = 60;

    private ImportLimits() {
    }
}
