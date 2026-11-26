package app.lightscout.domain.sound;

/**
 * Where a chosen alarm sound comes from (DCR-51).
 */
public enum SoundKind {
    /** The built-in "LightScout classic" tone, today's alarm sound. */
    CLASSIC,
    /** Another sound shipped inside the app. */
    BUNDLED,
    /** A phone alarm sound, copied into app storage when it was chosen. */
    PHONE,
    /** A file the user added, copied into app storage. */
    OWN
}
