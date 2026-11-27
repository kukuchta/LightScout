package app.lightscout.feature.settings;

/** A short message S-14 shows once after a choice or an import did not work (one per failure, no file name). */
enum PickerMessage {
    /** The file is above the size limit. */
    TOO_LARGE,
    /** The file or the phone sound could not be read. */
    CANNOT_READ,
    /** The file opened but cannot be played as an alarm sound. */
    NOT_PLAYABLE,
    /** The library already holds the most own sounds. */
    LIMIT_REACHED
}
