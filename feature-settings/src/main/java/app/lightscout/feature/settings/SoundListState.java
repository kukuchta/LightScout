package app.lightscout.feature.settings;

import app.lightscout.domain.sound.SoundLibrary;

/**
 * Immutable values shown by S-13.
 *
 * @param library the stored choices and sounds
 * @param loaded  {@code false} until the first answer of the service
 */
record SoundListState(SoundLibrary library, boolean loaded) {
}
