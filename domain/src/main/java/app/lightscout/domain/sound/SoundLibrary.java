package app.lightscout.domain.sound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import app.lightscout.domain.alarm.AlarmType;

/**
 * The user's alarm sounds (DCR-51): the choice per alarm type, up to {@link ImportLimits#MAX_OWN_SOUNDS} own sounds,
 * the phone sounds copied for the types that use them, and at most one removal that can still be undone. Immutable:
 * every change returns a new library. The rules that keep it consistent live here, so a stored library that was
 * damaged or edited is repaired on reading ({@link #of}).
 *
 * <p>Never contains a reference to a missing sound, and keeps a phone copy only while some type uses it.
 */
public final class SoundLibrary {

    /** No own sounds, no phone copies, every type on the classic tone. */
    public static final SoundLibrary EMPTY =
            new SoundLibrary(AlarmSoundChoice.allClassic(), List.of(), List.of(), null);

    private final AlarmSoundChoice choice;
    private final List<OwnSound> own;
    private final List<PhoneCopy> phone;
    private final Removal pending;

    private SoundLibrary(AlarmSoundChoice choice, List<OwnSound> own, List<PhoneCopy> phone, Removal pending) {
        this.choice = choice;
        this.own = own;
        this.phone = phone;
        this.pending = pending;
    }

    /**
     * Builds a library from stored parts and repairs it: duplicate keys are dropped, only the first five own sounds
     * are kept, a choice that points to a missing sound falls back to the classic tone, and a phone copy that no type
     * uses is dropped.
     *
     * @param choice  stored choice
     * @param own     stored own sounds
     * @param phone   stored phone copies
     * @param pending stored removal that can still be undone, or {@code null}
     * @return the repaired library
     */
    public static SoundLibrary of(AlarmSoundChoice choice, List<OwnSound> own, List<PhoneCopy> phone,
                                  Removal pending) {
        Objects.requireNonNull(choice, "choice");
        return build(choice, firstFive(own), phone, pending);
    }

    private static List<OwnSound> firstFive(List<OwnSound> sounds) {
        Map<String, OwnSound> byKey = new LinkedHashMap<>();
        for (OwnSound sound : sounds) {
            byKey.putIfAbsent(sound.key(), sound);
        }
        return byKey.values().stream().limit(ImportLimits.MAX_OWN_SOUNDS).toList();
    }

    private static SoundLibrary build(AlarmSoundChoice choice, List<OwnSound> own, List<PhoneCopy> phone,
                                      Removal pending) {
        Set<String> ownKeys = new TreeSet<>();
        own.forEach(sound -> ownKeys.add(sound.key()));
        Set<String> phoneKeys = new TreeSet<>();
        phone.forEach(copy -> phoneKeys.add(copy.key()));
        AlarmSoundChoice repaired = choice;
        Set<String> usedPhone = new TreeSet<>();
        for (AlarmType type : AlarmType.values()) {
            SoundRef ref = repaired.get(type);
            boolean missing = ref.kind() == SoundKind.OWN && !ownKeys.contains(ref.key())
                    || ref.kind() == SoundKind.PHONE && !phoneKeys.contains(ref.key());
            if (missing) {
                repaired = repaired.with(type, SoundRef.CLASSIC);
            } else if (ref.kind() == SoundKind.PHONE) {
                usedPhone.add(ref.key());
            }
        }
        Map<String, PhoneCopy> kept = new LinkedHashMap<>();
        for (PhoneCopy copy : phone) {
            if (usedPhone.contains(copy.key())) {
                kept.putIfAbsent(copy.key(), copy);
            }
        }
        return new SoundLibrary(repaired, List.copyOf(own), List.copyOf(kept.values()), pending);
    }

    /**
     * Returns the sound of every alarm type.
     *
     * @return the choice
     */
    public AlarmSoundChoice choice() {
        return choice;
    }

    /**
     * Returns the own sounds.
     *
     * @return up to five sounds in the order they were added; excludes a removal that can be undone
     */
    public List<OwnSound> ownSounds() {
        return own;
    }

    /**
     * Returns the phone sounds copied for the types that use them.
     *
     * @return the copies
     */
    public List<PhoneCopy> phoneCopies() {
        return phone;
    }

    /**
     * Returns the removal that can still be undone.
     *
     * @return the removal, or empty
     */
    public Optional<Removal> pendingRemoval() {
        return Optional.ofNullable(pending);
    }

    /**
     * Tells whether another own sound fits.
     *
     * @return {@code false} at {@link ImportLimits#MAX_OWN_SOUNDS}
     */
    public boolean canAddOwn() {
        return own.size() < ImportLimits.MAX_OWN_SOUNDS;
    }

    /**
     * Finds an own sound.
     *
     * @param key sound key
     * @return the sound, or empty
     */
    public Optional<OwnSound> ownSound(String key) {
        return own.stream().filter(sound -> sound.key().equals(key)).findFirst();
    }

    /**
     * Finds the copy of a phone sound that was copied from a URI.
     *
     * @param sourceUri URI the phone offered
     * @return the copy, or empty
     */
    public Optional<PhoneCopy> phoneCopyFor(String sourceUri) {
        return phone.stream().filter(copy -> copy.sourceUri().equals(sourceUri)).findFirst();
    }

    /**
     * Gives one alarm type a sound. A phone copy that no type uses any more is dropped.
     *
     * @param type alarm type
     * @param ref  the sound; an own sound or a phone copy must exist in this library
     * @return the new library
     * @throws IllegalArgumentException when an own sound or phone copy does not exist
     */
    public SoundLibrary select(AlarmType type, SoundRef ref) {
        boolean known = switch (ref.kind()) {
            case CLASSIC, BUNDLED -> true;
            case OWN -> ownSound(ref.key()).isPresent();
            case PHONE -> phone.stream().anyMatch(copy -> copy.key().equals(ref.key()));
        };
        SoundFields.require(known, "Unknown sound");
        return build(choice.with(type, ref), own, phone, pending);
    }

    /**
     * Adds an own sound. A removal that could be undone is finalised first.
     *
     * @param sound the sound
     * @return the new library
     * @throws IllegalStateException    when the library already has {@link ImportLimits#MAX_OWN_SOUNDS}
     * @throws IllegalArgumentException when the key exists
     */
    public SoundLibrary addOwn(OwnSound sound) {
        Objects.requireNonNull(sound, "sound");
        SoundFields.require(ownSound(sound.key()).isEmpty(), "Sound key exists");
        if (!canAddOwn()) {
            throw new IllegalStateException("At most " + ImportLimits.MAX_OWN_SOUNDS + " own sounds");
        }
        List<OwnSound> next = new ArrayList<>(own);
        next.add(sound);
        return build(choice, next, phone, null);
    }

    /**
     * Adds a phone copy. It is kept only once some type is given it with {@link #select}.
     *
     * @param copy the copy
     * @return the new library
     * @throws IllegalArgumentException when the key exists
     */
    public SoundLibrary addPhoneCopy(PhoneCopy copy) {
        Objects.requireNonNull(copy, "copy");
        SoundFields.require(phone.stream().noneMatch(other -> other.key().equals(copy.key())), "Copy key exists");
        List<PhoneCopy> next = new ArrayList<>(phone);
        next.add(copy);
        return new SoundLibrary(choice, own, List.copyOf(next), pending);
    }

    /**
     * Removes an own sound. Every type that used it goes back to the classic tone. The sound can be restored with
     * {@link #undoRemoval()} until the removal is finalised; a removal that was still open is finalised.
     *
     * @param key key of the sound
     * @return the new library
     * @throws IllegalArgumentException when no such sound exists
     */
    public SoundLibrary remove(String key) {
        OwnSound sound = ownSound(key).orElseThrow(() -> new IllegalArgumentException("Unknown sound"));
        SoundRef ref = SoundRef.own(key);
        List<AlarmType> reassigned = choice.typesUsing(ref);
        AlarmSoundChoice next = choice;
        for (AlarmType type : reassigned) {
            next = next.with(type, SoundRef.CLASSIC);
        }
        List<OwnSound> rest = own.stream().filter(other -> !other.key().equals(key)).toList();
        return build(next, rest, phone, new Removal(sound, reassigned));
    }

    /**
     * Restores the removed sound, and gives it back to the types that used it and are still on the classic tone.
     * Does nothing when no removal is open or the library is full.
     *
     * @return the new library, or this one
     */
    public SoundLibrary undoRemoval() {
        if (pending == null || !canAddOwn()) {
            return this;
        }
        List<OwnSound> next = new ArrayList<>(own);
        next.add(pending.sound());
        SoundRef ref = SoundRef.own(pending.sound().key());
        AlarmSoundChoice restored = choice;
        for (AlarmType type : pending.reassigned()) {
            if (SoundRef.CLASSIC.equals(restored.get(type))) {
                restored = restored.with(type, ref);
            }
        }
        return build(restored, next, phone, null);
    }

    /**
     * Closes the open removal, so it can no longer be undone.
     *
     * @return the new library, or this one
     */
    public SoundLibrary finaliseRemoval() {
        return pending == null ? this : new SoundLibrary(choice, own, phone, null);
    }

    /**
     * Returns the files that must stay on disk: own sounds, the file of a removal that can be undone, and the copies
     * of phone sounds that a type uses. Any other file in the sound directory is an orphan.
     *
     * @return plain file names
     */
    public Set<String> referencedFiles() {
        Set<String> files = new TreeSet<>();
        own.forEach(sound -> files.add(sound.fileName()));
        if (pending != null) {
            files.add(pending.sound().fileName());
        }
        phone.forEach(copy -> files.add(copy.fileName()));
        return Set.copyOf(files);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SoundLibrary library && choice.equals(library.choice) && own.equals(library.own)
                && phone.equals(library.phone) && Objects.equals(pending, library.pending);
    }

    @Override
    public int hashCode() {
        return Objects.hash(choice, own, phone, pending);
    }

    @Override
    public String toString() {
        return "SoundLibrary[" + choice + ", own=" + own.size() + ", phone=" + phone.size()
                + (pending == null ? "" : ", pending removal") + "]";
    }
}
