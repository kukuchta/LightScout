package app.lightscout.usecases.sound;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundStore;
import app.lightscout.domain.port.AudioSourcePort;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.sound.ImportLimits;
import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * DCR-51: the sound service (IF-70). A phone sound or a user file is only copied; a choice always points at a
 * complete file; a failed step leaves the stored library as it was and no file behind.
 */
public class AlarmSoundServiceTest {

    private static final String BYTES = "twelve bytes";
    private static final String PHONE_URI = "content://media/internal/audio/media/6";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private FakeStore store;
    private FakeSource source;
    private final List<String> events = new ArrayList<>();
    private int keyCounter;
    private AlarmSoundService service;

    @Before
    public void setUp() throws IOException {
        store = new FakeStore(folder.newFolder("sounds"));
        source = new FakeSource();
        Logger logger = new Logger() {
            @Override
            public void event(EventCode code) {
                events.add(code.name());
            }

            @Override
            public void event(EventCode code, String detail) {
                events.add(code.name() + ":" + detail);
            }

            @Override
            public void crash(String exceptionClass, String topAppFrame) {
                events.add("CRASH");
            }

            @Override
            public void debug(String tag, String message) {
                events.add("DEBUG");
            }
        };
        service = new DefaultAlarmSoundService(Runnable::run, store, source, logger,
                () -> String.format("%016x", ++keyCounter));
    }

    private static <T> T await(Consumer<Consumer<T>> call) {
        AtomicReference<T> out = new AtomicReference<>();
        call.accept(out::set);
        return out.get();
    }

    private SoundAction select(AlarmType type, SoundRef ref) {
        return await(cb -> service.select(type, ref, cb));
    }

    private SoundAction selectPhone(AlarmType type) {
        return await(cb -> service.selectPhone(type, new PhoneSound(PHONE_URI, "Beep"), cb));
    }

    private SoundAction addOwn(String name, AlarmType selectFor) {
        return await(cb -> service.addOwn("content://docs/1", name, selectFor, cb));
    }

    private SoundAction remove(int n) {
        return await(cb -> service.remove(key(n), cb));
    }

    private SoundAction undo() {
        return await(cb -> service.undoRemoval(cb));
    }

    private SoundAction finalise() {
        return await(cb -> service.finaliseRemoval(cb));
    }

    private static String key(int n) {
        return String.format("%016x", n);
    }

    @Test
    public void loadGivesTheStoredLibraryAndThePhoneSounds() {
        source.phone = List.of(new PhoneSound(PHONE_URI, "Beep"));
        AlarmSoundsView view = await(cb -> service.load(cb));
        assertEquals(SoundLibrary.EMPTY, view.library());
        assertEquals(source.phone, view.phoneSounds());
    }

    @Test
    public void loadWithUnreadableStorageGivesTheDefaultAndTheListStillComes() {
        store.failRead = true;
        source.phone = List.of(new PhoneSound(PHONE_URI, "Beep"));
        AlarmSoundsView view = await(cb -> service.load(cb));
        assertEquals(SoundLibrary.EMPTY, view.library());
        assertEquals(1, view.phoneSounds().size());
    }

    @Test
    public void selectingTheClassicToneOrABundledSoundNeedsNoFile() {
        SoundAction bundled = select(AlarmType.HIGH, SoundRef.bundled("chime"));
        assertEquals(ImportOutcome.OK, bundled.outcome());
        assertEquals(SoundRef.bundled("chime"), store.library.choice().get(AlarmType.HIGH));
        SoundAction classic = select(AlarmType.HIGH, SoundRef.CLASSIC);
        assertEquals(ImportOutcome.OK, classic.outcome());
        assertTrue(store.library.choice().customisedTypes().isEmpty());
        assertEquals(0, source.copies);
    }

    @Test
    public void selectingAnUnknownOwnSoundChangesNothing() {
        SoundAction result = select(AlarmType.LOW, SoundRef.own(key(9)));
        assertEquals(ImportOutcome.CANNOT_READ, result.outcome());
        assertEquals(0, store.commits);
    }

    @Test
    public void aPhoneSoundIsCopiedPublishedCommittedAndSelected() {
        SoundAction result = selectPhone(AlarmType.LOW);
        assertEquals(ImportOutcome.OK, result.outcome());
        String file = "phone-" + key(1);
        assertEquals(Set.of(file), store.files());
        assertEquals(SoundRef.phone(key(1)), store.library.choice().get(AlarmType.LOW));
        assertEquals(PHONE_URI, store.library.phoneCopies().get(0).sourceUri());
        assertEquals(BYTES.length(), store.library.phoneCopies().get(0).byteLength());
        assertEquals(result.library(), store.library);
    }

    @Test
    public void thePhoneCopyIsReusedForAnotherTypeAndRemovedWhenNoTypeUsesIt() {
        selectPhone(AlarmType.LOW);
        selectPhone(AlarmType.HIGH);
        assertEquals("one copy only", 1, source.copies);
        assertEquals(1, store.library.phoneCopies().size());
        select(AlarmType.LOW, SoundRef.CLASSIC);
        assertEquals("still used by HIGH", Set.of("phone-" + key(1)), store.files());
        select(AlarmType.HIGH, SoundRef.CLASSIC);
        assertTrue("no type uses it any more", store.files().isEmpty());
    }

    @Test
    public void aPhoneSoundThatCannotBeCopiedLeavesNothingBehind() {
        source.outcome = ImportOutcome.NOT_PLAYABLE;
        SoundAction result = selectPhone(AlarmType.LOW);
        assertEquals(ImportOutcome.NOT_PLAYABLE, result.outcome());
        assertEquals(0, store.commits);
        assertTrue(store.files().isEmpty());
        assertEquals(0, store.tempFilesLeft());
        assertEquals(List.of("SOUND_IMPORT_FAIL:NOT_PLAYABLE"), events);
    }

    @Test
    public void aFileIsAddedWithACleanedNameAndSelectedForTheOpenAlarm() {
        SoundAction result = addOwn("My alarm.mp3", AlarmType.URGENT_LOW);
        assertEquals(ImportOutcome.OK, result.outcome());
        OwnSound added = store.library.ownSounds().get(0);
        assertEquals("My alarm", added.displayName());
        assertEquals("own-" + key(1), added.fileName());
        assertEquals(SoundRef.own(key(1)), store.library.choice().get(AlarmType.URGENT_LOW));
        assertEquals(Set.of(added.fileName()), store.files());
    }

    @Test
    public void aFileAddedWithoutAnAlarmIsOnlyListed() {
        addOwn("x.ogg", null);
        assertEquals(1, store.library.ownSounds().size());
        assertTrue(store.library.choice().customisedTypes().isEmpty());
    }

    @Test
    public void anEmptyNameBecomesSoundN() {
        addOwn("", null);
        addOwn(null, null);
        assertEquals("Sound 1", store.library.ownSounds().get(0).displayName());
        assertEquals("Sound 2", store.library.ownSounds().get(1).displayName());
    }

    @Test
    public void theSixthFileIsRefusedBeforeAnythingIsCopied() {
        for (int i = 0; i < ImportLimits.MAX_OWN_SOUNDS; i++) {
            assertEquals(ImportOutcome.OK, addOwn("s" + i, null).outcome());
        }
        int copies = source.copies;
        SoundAction sixth = addOwn("s6", null);
        assertEquals(ImportOutcome.LIMIT_REACHED, sixth.outcome());
        assertEquals(copies, source.copies);
        assertEquals(5, store.library.ownSounds().size());
        assertEquals(List.of("SOUND_IMPORT_FAIL:LIMIT_REACHED"), events);
    }

    @Test
    public void aFileThatIsTooLargeIsRefusedAndNothingStays() {
        source.outcome = ImportOutcome.TOO_LARGE;
        assertEquals(ImportOutcome.TOO_LARGE, addOwn("big.wav", null).outcome());
        assertEquals(ImportLimits.MAX_SOURCE_BYTES, source.lastMaxBytes);
        assertTrue(store.files().isEmpty());
        assertEquals(0, store.tempFilesLeft());
        assertEquals(0, store.commits);
    }

    @Test
    public void aFailedCommitDeletesThePublishedFileAndKeepsTheOldLibrary() {
        SoundLibrary before = store.library;
        store.failCommit = true;
        SoundAction result = addOwn("x.mp3", AlarmType.LOW);
        assertEquals(ImportOutcome.CANNOT_READ, result.outcome());
        assertTrue("the published file is removed", store.files().isEmpty());
        assertEquals(before, store.library);
        assertEquals(before, result.library());
    }

    @Test
    public void aCopyThatReportsNoBytesIsNotAccepted() {
        source.bytes = "";
        assertEquals(ImportOutcome.NOT_PLAYABLE, addOwn("empty.mp3", null).outcome());
        assertTrue(store.files().isEmpty());
        assertEquals(0, store.tempFilesLeft());
    }

    @Test
    public void removeKeepsTheFileUntilTheRemovalIsFinalised() {
        addOwn("a.mp3", AlarmType.LOW);
        String file = store.library.ownSounds().get(0).fileName();
        SoundAction removed = remove(1);
        assertEquals(ImportOutcome.OK, removed.outcome());
        assertEquals(SoundRef.CLASSIC, store.library.choice().get(AlarmType.LOW));
        assertTrue("Undo needs the file", store.files().contains(file));
        finalise();
        assertFalse(store.files().contains(file));
    }

    @Test
    public void undoRestoresTheSoundAndItsChoice() {
        addOwn("a.mp3", AlarmType.LOW);
        remove(1);
        SoundAction undone = undo();
        assertEquals(ImportOutcome.OK, undone.outcome());
        assertEquals(SoundRef.own(key(1)), store.library.choice().get(AlarmType.LOW));
        assertEquals(1, store.files().size());
    }

    @Test
    public void addingASoundFinalisesAnOpenRemovalAndDeletesItsFile() {
        addOwn("a.mp3", null);
        String oldFile = store.library.ownSounds().get(0).fileName();
        remove(1);
        addOwn("b.mp3", null);
        assertFalse(store.files().contains(oldFile));
        assertEquals(1, store.files().size());
    }

    @Test
    public void theSweepFinalisesAnOpenRemovalAndRemovesOrphans() throws IOException {
        addOwn("a.mp3", null);
        String removedFile = store.library.ownSounds().get(0).fileName();
        addOwn("b.mp3", null);
        remove(1);
        store.plant("own-orphan");
        service.sweep();
        assertTrue(store.library.pendingRemoval().isEmpty());
        assertEquals(Set.of("own-" + key(2)), store.files());
        assertFalse(store.files().contains(removedFile));
    }

    @Test
    public void theSweepWithUnreadableStorageDoesNotDeleteAnything() {
        addOwn("a.mp3", null);
        store.failRead = true;
        service.sweep();
        assertEquals(1, store.files().size());
    }

    @Test
    public void anUnexpectedExceptionStillAnswersTheCallbackOnce() {
        store.crashOnTemp = true;
        SoundAction result = addOwn("x.mp3", AlarmType.LOW);
        assertEquals(ImportOutcome.CANNOT_READ, result.outcome());
        assertEquals(0, store.commits);
        assertEquals(List.of("SOUND_IMPORT_FAIL:CANNOT_READ"), events);
    }

    @Test
    public void aFailedCommitOfAPhoneSelectionRemovesThePhoneFile() {
        store.failCommit = true;
        SoundAction result = selectPhone(AlarmType.LOW);
        assertEquals(ImportOutcome.CANNOT_READ, result.outcome());
        assertTrue("no orphan phone file", store.files().isEmpty());
        assertEquals(0, store.tempFilesLeft());
    }

    @Test
    public void aFailedCleanupAfterASuccessfulCommitIsStillASuccess() {
        selectPhone(AlarmType.LOW);
        store.failDelete = true;
        SoundAction result = select(AlarmType.LOW, SoundRef.CLASSIC);
        assertEquals("the change was made", ImportOutcome.OK, result.outcome());
        assertEquals(result.library(), store.library);
        assertTrue(store.library.choice().customisedTypes().isEmpty());
        assertTrue("the next sweep removes the file", events.isEmpty());
    }

    @Test
    public void undoOnAFullLibraryIsReportedAsNotDone() {
        List<OwnSound> five = new ArrayList<>();
        for (int i = 1; i <= ImportLimits.MAX_OWN_SOUNDS; i++) {
            five.add(new OwnSound(key(i), "Sound " + i, "own-" + key(i), 10));
        }
        OwnSound removed = new OwnSound(key(9), "Removed", "own-" + key(9), 10);
        // five own sounds and a removal that is still open: there is no room to restore it
        store.library = SoundLibrary.of(app.lightscout.domain.sound.AlarmSoundChoice.allClassic(), five, List.of(),
                new app.lightscout.domain.sound.Removal(removed, List.of()));
        SoundAction undone = undo();
        assertEquals(ImportOutcome.CANNOT_READ, undone.outcome());
        assertTrue(store.library.pendingRemoval().isPresent());
    }

    /** In-memory {@link AlarmSoundStore} over a real temporary folder. */
    private static final class FakeStore implements AlarmSoundStore {
        private final File dir;
        private final File tmp;
        SoundLibrary library = SoundLibrary.EMPTY;
        boolean failRead;
        boolean failCommit;
        boolean failDelete;
        boolean crashOnTemp;
        int commits;
        private int tempCounter;

        FakeStore(File dir) {
            this.dir = dir;
            this.tmp = new File(dir, "tmp");
            tmp.mkdirs();
        }

        @Override
        public SoundLibrary read() {
            if (failRead) {
                throw new StorageException("read", null);
            }
            return library;
        }

        @Override
        public void commit(SoundLibrary next) {
            if (failCommit) {
                throw new StorageException("commit", null);
            }
            commits++;
            library = next;
        }

        @Override
        public File newTempFile() {
            if (crashOnTemp) {
                throw new NullPointerException("a store bug");
            }
            try {
                File f = new File(tmp, "t" + (++tempCounter) + ".part");
                f.createNewFile();
                return f;
            } catch (IOException e) {
                throw new StorageException("temp", e);
            }
        }

        @Override
        public String publish(File temp, String fileName) {
            try {
                Files.move(temp.toPath(), new File(dir, fileName).toPath());
                return fileName;
            } catch (IOException e) {
                throw new StorageException("publish", e);
            }
        }

        @Override
        public void deleteFiles(Set<String> fileNames) {
            if (failDelete) {
                throw new StorageException("delete", null);
            }
            Set<String> used = library.referencedFiles();
            for (String name : fileNames) {
                if (!used.contains(name)) {
                    new File(dir, name).delete();
                }
            }
        }

        @Override
        public void sweep(Set<String> keep) {
            Set<String> kept = new HashSet<>(keep);
            kept.addAll(library.referencedFiles());
            for (String name : files()) {
                if (!kept.contains(name)) {
                    new File(dir, name).delete();
                }
            }
            for (File t : tmp.listFiles()) {
                t.delete();
            }
        }

        Set<String> files() {
            Set<String> out = new HashSet<>();
            for (File f : dir.listFiles()) {
                if (f.isFile()) {
                    out.add(f.getName());
                }
            }
            return out;
        }

        int tempFilesLeft() {
            return tmp.listFiles().length;
        }

        void plant(String name) throws IOException {
            new File(dir, name).createNewFile();
        }
    }

    /** {@link AudioSourcePort} that writes fixed bytes. */
    private static final class FakeSource implements AudioSourcePort {
        List<PhoneSound> phone = List.of();
        ImportOutcome outcome = ImportOutcome.OK;
        String bytes = BYTES;
        int copies;
        long lastMaxBytes;

        @Override
        public List<PhoneSound> phoneAlarmSounds() {
            return phone;
        }

        @Override
        public ImportOutcome copyTo(String uri, File target, long maxBytes, BooleanSupplier cancelled) {
            copies++;
            lastMaxBytes = maxBytes;
            if (outcome != ImportOutcome.OK) {
                return outcome;
            }
            try {
                Files.write(target.toPath(), bytes.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return ImportOutcome.CANNOT_READ;
            }
            return ImportOutcome.OK;
        }
    }
}
