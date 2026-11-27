package app.lightscout.feature.settings;

import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundLibrary;

import java.util.List;

/**
 * Immutable values shown by S-14.
 *
 * @param library      the stored choices, own sounds, phone copies and the open removal
 * @param phoneSounds  the alarm sounds the phone offers
 * @param loaded       {@code false} until the first answer of the service
 * @param busyPhoneUri URI of the phone sound that is being copied, or {@code null}
 * @param adding       {@code true} while a picked file is being copied
 */
record SoundPickerState(SoundLibrary library, List<PhoneSound> phoneSounds, boolean loaded, String busyPhoneUri,
                        boolean adding) {

    SoundPickerState {
        phoneSounds = List.copyOf(phoneSounds);
    }
}
