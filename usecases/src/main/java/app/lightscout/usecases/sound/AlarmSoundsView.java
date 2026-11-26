package app.lightscout.usecases.sound;

import java.util.List;

import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundLibrary;

/**
 * What the sound screens show (DCR-51): the stored library and the phone's alarm sounds.
 *
 * @param library     the stored choices, own sounds and phone copies
 * @param phoneSounds the alarm sounds the phone offers; empty when it offers none or the list cannot be read
 */
public record AlarmSoundsView(SoundLibrary library, List<PhoneSound> phoneSounds) {

    /**
     * Copies the list.
     */
    public AlarmSoundsView {
        phoneSounds = List.copyOf(phoneSounds);
    }
}
