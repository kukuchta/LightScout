package app.lightscout.domain.port;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.PhoneSound;

/**
 * Source of sounds outside the app (IF-68, DCR-51), implemented by {@code :platform}: the phone's alarm sounds and
 * the file the user picked. Blocking: call off the main thread (a cursor read is disk access).
 */
public interface AudioSourcePort {

    /**
     * Lists the phone's alarm sounds. Needs no permission.
     *
     * @return the sounds, empty on any failure
     */
    List<PhoneSound> phoneAlarmSounds();

    /**
     * Copies a sound byte for byte into a file and checks that the player can open the copy and reports a positive
     * duration. Nothing is decoded or changed.
     *
     * @param uri       content URI of a phone sound or of the picked file
     * @param target    empty file to write, from {@link AlarmSoundStore#newTempFile()}
     * @param maxBytes  largest accepted size in bytes
     * @param cancelled polled during the copy; {@code true} stops it
     * @return {@link ImportOutcome#OK}, or the reason the copy cannot be used; {@code target} is then not usable
     */
    ImportOutcome copyTo(String uri, File target, long maxBytes, BooleanSupplier cancelled);
}
