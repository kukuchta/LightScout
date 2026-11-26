package app.lightscout.domain.sound;

/**
 * Result of copying a phone sound or a user file into app storage (DCR-51). The user sees one generic message for
 * every failure; the code is only for the log (SR-07: never a name, URI or path).
 */
public enum ImportOutcome {
    /** The file was copied and the player can open it. */
    OK,
    /** The file is larger than {@link ImportLimits#MAX_SOURCE_BYTES}. */
    TOO_LARGE,
    /** The source could not be opened or read, or the copy could not be written. */
    CANNOT_READ,
    /** The player cannot open the copy or reports no positive duration. */
    NOT_PLAYABLE,
    /** The user already has {@link ImportLimits#MAX_OWN_SOUNDS} own sounds. */
    LIMIT_REACHED,
    /** The user left before the copy finished. */
    CANCELLED
}
