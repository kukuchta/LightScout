package app.lightscout.usecases.sound;

import java.util.function.Consumer;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundRef;

/**
 * IF-70 alarm sound service (DCR-51). Work and callbacks run on {@code ioExecutor}; a callback comes exactly once. A
 * phone sound or a user file is only copied into app storage; a choice always points at a complete file. All changes
 * run one at a time. A failed step leaves the stored library as it was and no file behind.
 */
public interface AlarmSoundService {

    /**
     * Reads the library and the phone's alarm sounds.
     *
     * @param cb receives the view; a library that cannot be read shows as the default, every type on classic
     */
    void load(Consumer<AlarmSoundsView> cb);

    /**
     * Gives an alarm type a sound that is already in the library (classic, bundled, own or a phone copy).
     *
     * @param type alarm type
     * @param ref  the sound
     * @param cb   receives the result
     */
    void select(AlarmType type, SoundRef ref, Consumer<SoundAction> cb);

    /**
     * Gives an alarm type a phone sound: copies it first, or reuses the copy when it was copied before.
     *
     * @param type  alarm type
     * @param sound the phone sound from {@link #load}
     * @param cb    receives the result
     */
    void selectPhone(AlarmType type, PhoneSound sound, Consumer<SoundAction> cb);

    /**
     * Adds a file the user picked. An open removal that could be undone is finalised.
     *
     * @param documentUri content URI of the picked file
     * @param displayName the provider's name for it; cleaned here, {@code null} or empty allowed
     * @param selectFor   the alarm type whose picker is open, which gets the new sound; {@code null} only lists it
     * @param cb          receives the result
     */
    void addOwn(String documentUri, String displayName, AlarmType selectFor, Consumer<SoundAction> cb);

    /**
     * Removes an own sound. Every type that used it goes back to the classic tone. The file stays until
     * {@link #finaliseRemoval} so that {@link #undoRemoval} works.
     *
     * @param ownKey key of the own sound
     * @param cb     receives the result
     */
    void remove(String ownKey, Consumer<SoundAction> cb);

    /**
     * Restores the removed sound and gives it back to the types that used it and are still on classic.
     *
     * @param cb receives the result
     */
    void undoRemoval(Consumer<SoundAction> cb);

    /**
     * Closes the open removal and deletes its file.
     *
     * @param cb receives the result
     */
    void finaliseRemoval(Consumer<SoundAction> cb);

    /**
     * Start-up clean-up: closes an open removal and deletes every file the library does not name, and unfinished
     * copies. Run once per process, after unlock, never while an import runs.
     */
    void sweep();
}
