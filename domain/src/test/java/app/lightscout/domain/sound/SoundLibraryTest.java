package app.lightscout.domain.sound;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import app.lightscout.domain.alarm.AlarmType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** DCR-51: the pure rules of the sound library (choices, own sounds, phone copies, one pending removal). */
public class SoundLibraryTest {

    private static String key(int n) {
        return String.format("%016x", n);
    }

    private static OwnSound own(int n) {
        return new OwnSound(key(n), "Sound " + n, "own-" + key(n), 1000L + n);
    }

    private static PhoneCopy phone(int n) {
        return new PhoneCopy(key(100 + n), "content://media/internal/audio/media/" + n, "Phone " + n,
                "phone-" + key(100 + n), 2000L + n);
    }

    private static SoundLibrary withFive() {
        SoundLibrary library = SoundLibrary.EMPTY;
        for (int i = 1; i <= ImportLimits.MAX_OWN_SOUNDS; i++) {
            library = library.addOwn(own(i));
        }
        return library;
    }

    @Test
    public void emptyLibraryIsAllClassicWithNothingToKeep() {
        assertTrue(SoundLibrary.EMPTY.choice().customisedTypes().isEmpty());
        assertTrue(SoundLibrary.EMPTY.ownSounds().isEmpty());
        assertTrue(SoundLibrary.EMPTY.referencedFiles().isEmpty());
        assertTrue(SoundLibrary.EMPTY.pendingRemoval().isEmpty());
        assertTrue(SoundLibrary.EMPTY.canAddOwn());
    }

    @Test
    public void everyTypeSelectsEveryKindOnItsOwn() {
        SoundLibrary base = SoundLibrary.EMPTY.addOwn(own(1)).addPhoneCopy(phone(1));
        SoundRef[] refs = {SoundRef.CLASSIC, SoundRef.bundled("chime"), SoundRef.own(key(1)),
                SoundRef.phone(key(101))};
        for (AlarmType type : AlarmType.values()) {
            for (SoundRef ref : refs) {
                SoundLibrary next = base.select(type, ref);
                assertEquals(ref, next.choice().get(type));
                for (AlarmType other : AlarmType.values()) {
                    if (other != type) {
                        assertEquals(SoundRef.CLASSIC, next.choice().get(other));
                    }
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void selectingAnUnknownOwnSoundIsRefused() {
        SoundLibrary.EMPTY.select(AlarmType.LOW, SoundRef.own(key(9)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void selectingAnUnknownPhoneCopyIsRefused() {
        SoundLibrary.EMPTY.select(AlarmType.LOW, SoundRef.phone(key(109)));
    }

    @Test
    public void theSixthOwnSoundIsRefused() {
        SoundLibrary full = withFive();
        assertFalse(full.canAddOwn());
        try {
            full.addOwn(own(6));
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(5, full.ownSounds().size());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void aDuplicateOwnKeyIsRefused() {
        SoundLibrary.EMPTY.addOwn(own(1)).addOwn(own(1));
    }

    @Test
    public void removalReassignsOnlyTheTypesThatUsedTheSound() {
        SoundLibrary library = SoundLibrary.EMPTY.addOwn(own(1)).addOwn(own(2))
                .select(AlarmType.LOW, SoundRef.own(key(1)))
                .select(AlarmType.HIGH, SoundRef.own(key(1)))
                .select(AlarmType.NO_DATA, SoundRef.own(key(2)));
        SoundLibrary removed = library.remove(key(1));
        assertEquals(SoundRef.CLASSIC, removed.choice().get(AlarmType.LOW));
        assertEquals(SoundRef.CLASSIC, removed.choice().get(AlarmType.HIGH));
        assertEquals(SoundRef.own(key(2)), removed.choice().get(AlarmType.NO_DATA));
        assertEquals(List.of(own(2)), removed.ownSounds());
        Removal pending = removed.pendingRemoval().orElseThrow();
        assertEquals(own(1), pending.sound());
        assertEquals(List.of(AlarmType.LOW, AlarmType.HIGH), pending.reassigned());
    }

    @Test
    public void removingAnUnusedSoundReassignsNothing() {
        SoundLibrary removed = SoundLibrary.EMPTY.addOwn(own(1)).remove(key(1));
        assertTrue(removed.pendingRemoval().orElseThrow().reassigned().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void removingAnUnknownSoundIsRefused() {
        SoundLibrary.EMPTY.remove(key(1));
    }

    @Test
    public void undoRestoresTheSoundAndOnlyTypesStillOnClassic() {
        SoundLibrary library = SoundLibrary.EMPTY.addOwn(own(1)).addOwn(own(2))
                .select(AlarmType.LOW, SoundRef.own(key(1)))
                .select(AlarmType.HIGH, SoundRef.own(key(1)))
                .remove(key(1))
                .select(AlarmType.HIGH, SoundRef.own(key(2)));
        SoundLibrary undone = library.undoRemoval();
        assertEquals(SoundRef.own(key(1)), undone.choice().get(AlarmType.LOW));
        assertEquals(SoundRef.own(key(2)), undone.choice().get(AlarmType.HIGH));
        assertEquals(2, undone.ownSounds().size());
        assertTrue(undone.pendingRemoval().isEmpty());
    }

    @Test
    public void undoWithNothingPendingChangesNothing() {
        SoundLibrary library = SoundLibrary.EMPTY.addOwn(own(1));
        assertEquals(library, library.undoRemoval());
    }

    @Test
    public void finaliseDropsThePendingRemoval() {
        SoundLibrary finalised = SoundLibrary.EMPTY.addOwn(own(1)).remove(key(1)).finaliseRemoval();
        assertTrue(finalised.pendingRemoval().isEmpty());
        assertTrue(finalised.ownSounds().isEmpty());
    }

    @Test
    public void addingASoundFinalisesAPendingRemoval() {
        SoundLibrary library = withFive().remove(key(5)).addOwn(own(6));
        assertTrue(library.pendingRemoval().isEmpty());
        assertEquals(5, library.ownSounds().size());
    }

    @Test
    public void removingASecondSoundFinalisesTheFirst() {
        SoundLibrary library = SoundLibrary.EMPTY.addOwn(own(1)).addOwn(own(2)).remove(key(1)).remove(key(2));
        assertEquals(own(2), library.pendingRemoval().orElseThrow().sound());
        assertTrue(library.ownSounds().isEmpty());
    }

    @Test
    public void referencedFilesCoverOwnSoundsPendingRemovalAndUsedPhoneCopiesOnly() {
        SoundLibrary library = SoundLibrary.EMPTY.addOwn(own(1)).addOwn(own(2))
                .addPhoneCopy(phone(1)).addPhoneCopy(phone(2))
                .select(AlarmType.LOW, SoundRef.phone(key(101)))
                .remove(key(2));
        assertEquals(Set.of(own(1).fileName(), own(2).fileName(), phone(1).fileName()), library.referencedFiles());
    }

    @Test
    public void aPhoneCopySharedByTwoTypesStaysUntilBothLeave() {
        SoundLibrary library = SoundLibrary.EMPTY.addPhoneCopy(phone(1))
                .select(AlarmType.LOW, SoundRef.phone(key(101)))
                .select(AlarmType.HIGH, SoundRef.phone(key(101)));
        SoundLibrary one = library.select(AlarmType.LOW, SoundRef.CLASSIC);
        assertEquals(Set.of(phone(1).fileName()), one.referencedFiles());
        assertEquals(1, one.phoneCopies().size());
        SoundLibrary none = one.select(AlarmType.HIGH, SoundRef.CLASSIC);
        assertTrue(none.referencedFiles().isEmpty());
        assertTrue(none.phoneCopies().isEmpty());
    }

    @Test
    public void phoneCopyIsFoundBySourceUri() {
        SoundLibrary library = SoundLibrary.EMPTY.addPhoneCopy(phone(3));
        assertEquals(phone(3), library.phoneCopyFor(phone(3).sourceUri()).orElseThrow());
        assertTrue(library.phoneCopyFor("content://other").isEmpty());
    }

    @Test
    public void readingFromStorageDropsReferencesToMissingEntries() {
        AlarmSoundChoice choice = AlarmSoundChoice.allClassic()
                .with(AlarmType.LOW, SoundRef.own(key(7)))
                .with(AlarmType.HIGH, SoundRef.phone(key(107)))
                .with(AlarmType.NO_DATA, SoundRef.bundled("chime"));
        SoundLibrary library = SoundLibrary.of(choice, List.of(), List.of(phone(8)), null);
        assertEquals(SoundRef.CLASSIC, library.choice().get(AlarmType.LOW));
        assertEquals(SoundRef.CLASSIC, library.choice().get(AlarmType.HIGH));
        assertEquals(SoundRef.bundled("chime"), library.choice().get(AlarmType.NO_DATA));
        assertTrue("an unused phone copy is dropped", library.phoneCopies().isEmpty());
    }

    @Test
    public void readingFromStorageKeepsOnlyFiveOwnSounds() {
        List<OwnSound> six = List.of(own(1), own(2), own(3), own(4), own(5), own(6));
        assertEquals(5, SoundLibrary.of(AlarmSoundChoice.allClassic(), six, List.of(), null).ownSounds().size());
    }

    @Test
    public void entriesRefuseUnsafeFileNamesAndNames() {
        assertThrowsIllegal(() -> new OwnSound(key(1), "x", "../own", 10));
        assertThrowsIllegal(() -> new OwnSound(key(1), "x", "dir/own", 10));
        assertThrowsIllegal(() -> new OwnSound(key(1), " ", "own-a", 10));
        assertThrowsIllegal(() -> new OwnSound(key(1), "x".repeat(61), "own-a", 10));
        assertThrowsIllegal(() -> new OwnSound(key(1), "x", "own-a", 0));
        assertThrowsIllegal(() -> new OwnSound(key(1), "x", "own-a", ImportLimits.MAX_SOURCE_BYTES + 1));
        assertThrowsIllegal(() -> new OwnSound("short", "x", "own-a", 10));
    }

    private static void assertThrowsIllegal(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
