package app.lightscout.usecases.sound;

import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.SoundLibrary;

/**
 * Result of a change to the sounds (DCR-51).
 *
 * @param outcome {@link ImportOutcome#OK} when the change was made, otherwise why it was not; the user sees one
 *                generic message for every failure
 * @param library what is stored now: the new library on success, the unchanged one on failure
 */
public record SoundAction(ImportOutcome outcome, SoundLibrary library) {
}
