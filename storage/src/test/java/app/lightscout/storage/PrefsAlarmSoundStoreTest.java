package app.lightscout.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.PlayableSound;
import app.lightscout.domain.sound.SoundKind;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DCR-51: the sound library store (IF-67) and the read-only view for the alarm (IF-69). The alarm must get a sound
 * whatever is wrong with the preferences or the files: every problem answers with the classic tone.
 */
public class PrefsAlarmSoundStoreTest {

    private static final String KEY_A = "00000000000000a1";
    private static final String KEY_C = "00000000000000c3";
    private static final String FILE_A = "own-" + KEY_A;
    private static final String FILE_C = "phone-" + KEY_C;
    private static final String BYTES = "twelve bytes";   // 12 bytes

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final FakeSharedPreferences prefs = new FakeSharedPreferences();
    private final RecordingLogger logger = new RecordingLogger();

    private SoundFiles files;
    private PrefsAlarmSoundStore store;

    private void open() {
        files = new SoundFiles(new File(folder.getRoot(), "alarm_sounds"));
        store = new PrefsAlarmSoundStore(prefs, files, new StoreGuard(logger));
    }

    private PrefsAlarmSoundStore reopen() {
        return new PrefsAlarmSoundStore(prefs, files, new StoreGuard(logger));
    }

    private String publish(String name, String text) throws IOException {
        File temp = store.newTempFile();
        Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return store.publish(temp, name);
    }

    private static OwnSound ownA() {
        return new OwnSound(KEY_A, "Morning beep", FILE_A, BYTES.length());
    }

    private static PhoneCopy phoneC() {
        return new PhoneCopy(KEY_C, "content://media/internal/audio/media/6", "Beep", FILE_C, BYTES.length());
    }

    private SoundLibrary libraryWithOwnOnLow() {
        return SoundLibrary.EMPTY.addOwn(ownA()).select(AlarmType.LOW, SoundRef.own(KEY_A));
    }

    @Test
    public void emptyPreferencesReadAsAnEmptyLibrary() {
        open();
        assertEquals(SoundLibrary.EMPTY, store.read());
    }

    @Test
    public void aCommittedLibraryIsReadBackByANewStore() {
        open();
        SoundLibrary library = libraryWithOwnOnLow().addPhoneCopy(phoneC())
                .select(AlarmType.HIGH, SoundRef.phone(KEY_C));
        store.commit(library);
        assertEquals(library, reopen().read());
    }

    @Test
    public void commitStoresOnlyNonClassicChoicesAndStampsTheSchema() {
        open();
        store.commit(libraryWithOwnOnLow());
        long choiceKeys = prefs.values.keySet().stream().filter(k -> k.startsWith("choice.")).count();
        assertEquals(1L, choiceKeys);
        assertEquals("own:" + KEY_A, prefs.values.get(PrefsAlarmSoundStore.KEY_CHOICE_LOW));
        assertEquals(PrefsSchema.CURRENT, prefs.values.get(PrefsSchema.KEY));
        assertEquals(1, prefs.commits);
    }

    @Test
    public void movingBackToClassicRemovesTheChoiceKey() {
        open();
        store.commit(libraryWithOwnOnLow());
        store.commit(libraryWithOwnOnLow().select(AlarmType.LOW, SoundRef.CLASSIC));
        assertFalse(prefs.values.containsKey(PrefsAlarmSoundStore.KEY_CHOICE_LOW));
    }

    @Test
    public void aFailedCommitKeepsTheOldSnapshotAndLogsOnce() {
        open();
        SoundLibrary old = libraryWithOwnOnLow();
        store.commit(old);
        prefs.failCommits = true;
        try {
            store.commit(SoundLibrary.EMPTY);
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertEquals(1L, logger.lines.stream().filter(l -> l.startsWith("STORAGE_ERROR")).count());
        }
        assertEquals(old, store.read());
        assertEquals(SoundRef.own(KEY_A), reopenChoice(AlarmType.LOW));
    }

    private SoundRef reopenChoice(AlarmType type) {
        prefs.failCommits = false;
        return reopen().read().choice().get(type);
    }

    @Test
    public void aFailedReadIsMappedToStorageException() {
        open();
        prefs.failReads = true;
        try {
            store.read();
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertFalse(logger.lines.isEmpty());
        }
    }

    @Test
    public void theAlarmGetsTheClassicToneByDefault() {
        open();
        for (AlarmType type : AlarmType.values()) {
            assertEquals(PlayableSound.CLASSIC, store.playable(type));
        }
    }

    @Test
    public void anOwnSoundWithItsCompleteFileIsPlayedFromTheFile() throws IOException {
        open();
        publish(FILE_A, BYTES);
        store.commit(libraryWithOwnOnLow());
        PlayableSound playable = store.playable(AlarmType.LOW);
        assertEquals(SoundKind.OWN, playable.kind());
        assertEquals(files.fileFor(FILE_A).getAbsolutePath(), playable.name());
        assertEquals(BYTES.length(), playable.byteLength());
        assertEquals("other types stay classic", PlayableSound.CLASSIC, store.playable(AlarmType.HIGH));
    }

    @Test
    public void aPhoneCopyIsPlayedFromItsFileToo() throws IOException {
        open();
        publish(FILE_C, BYTES);
        store.commit(SoundLibrary.EMPTY.addPhoneCopy(phoneC()).select(AlarmType.NO_DATA, SoundRef.phone(KEY_C)));
        assertEquals(files.fileFor(FILE_C).getAbsolutePath(), store.playable(AlarmType.NO_DATA).name());
    }

    @Test
    public void aMissingFileFallsBackToClassic() {
        open();
        store.commit(libraryWithOwnOnLow());
        assertEquals(PlayableSound.CLASSIC, store.playable(AlarmType.LOW));
    }

    @Test
    public void aFileOfTheWrongSizeFallsBackToClassic() throws IOException {
        open();
        publish(FILE_A, BYTES + "!");
        store.commit(libraryWithOwnOnLow());
        assertEquals(PlayableSound.CLASSIC, store.playable(AlarmType.LOW));
    }

    @Test
    public void aBundledChoiceIsHandedOnForThePlayerToCheck() {
        open();
        store.commit(SoundLibrary.EMPTY.select(AlarmType.HIGH, SoundRef.bundled("chime")));
        assertEquals(PlayableSound.bundled("chime"), store.playable(AlarmType.HIGH));
    }

    @Test
    public void unreadablePreferencesAtAlarmTimeGiveTheClassicTone() {
        open();
        prefs.failReads = true;
        assertEquals(PlayableSound.CLASSIC, store.playable(AlarmType.URGENT_LOW));
        assertEquals(PlayableSound.CLASSIC, store.playable(AlarmType.URGENT_LOW));
    }

    @Test
    public void aCommitReplacesWhatTheAlarmSeesAtOnceWithoutReading() throws IOException {
        open();
        publish(FILE_A, BYTES);
        assertEquals(PlayableSound.CLASSIC, store.playable(AlarmType.LOW));
        store.commit(libraryWithOwnOnLow());
        prefs.failReads = true;
        assertEquals(SoundKind.OWN, store.playable(AlarmType.LOW).kind());
    }

    @Test
    public void corruptLibraryTextGivesClassicForOwnSoundsButKeepsBundledChoices() {
        prefs.values.put(PrefsAlarmSoundStore.KEY_CHOICE_LOW, "own:" + KEY_A);
        prefs.values.put(PrefsAlarmSoundStore.KEY_CHOICE_HIGH, "bundled:chime");
        prefs.values.put(PrefsAlarmSoundStore.KEY_LIBRARY, "{broken");
        open();
        assertEquals(PlayableSound.CLASSIC, store.playable(AlarmType.LOW));
        assertEquals(PlayableSound.bundled("chime"), store.playable(AlarmType.HIGH));
    }

    @Test
    public void aChoiceThatDoesNotParseReadsAsClassic() {
        prefs.values.put(PrefsAlarmSoundStore.KEY_CHOICE_LOW, "mp3:evil");
        open();
        assertEquals(SoundRef.CLASSIC, store.read().choice().get(AlarmType.LOW));
    }

    @Test
    public void deleteFilesNeverRemovesAFileTheCommittedLibraryStillUses() throws IOException {
        open();
        publish(FILE_A, BYTES);
        publish("own-other", BYTES);
        store.commit(libraryWithOwnOnLow());
        store.deleteFiles(Set.of(FILE_A, "own-other"));
        assertTrue(files.fileFor(FILE_A).exists());
        assertFalse(files.fileFor("own-other").exists());
    }

    @Test
    public void sweepKeepsTheListedFilesOnly() throws IOException {
        open();
        publish(FILE_A, BYTES);
        publish("own-orphan", BYTES);
        store.sweep(Set.of(FILE_A));
        assertTrue(files.fileFor(FILE_A).exists());
        assertFalse(files.fileFor("own-orphan").exists());
    }

    @Test
    public void sweepNeverRemovesAFileTheCommittedLibraryStillUses() throws IOException {
        open();
        publish(FILE_A, BYTES);
        store.commit(libraryWithOwnOnLow());
        store.sweep(Set.of());
        assertTrue(files.fileFor(FILE_A).exists());
    }

    @Test
    public void sweepKeepsTheFileOfARemovalThatCanStillBeUndone() throws IOException {
        open();
        publish(FILE_A, BYTES);
        store.commit(libraryWithOwnOnLow().remove(KEY_A));
        store.sweep(Set.of());
        assertTrue(files.fileFor(FILE_A).exists());
    }

    @Test
    public void unplayableReportsAreKeptUntilTheNextCommit() {
        open();
        store.reportUnplayable(AlarmType.LOW);
        store.reportUnplayable(AlarmType.LOW);
        assertEquals(Set.of(AlarmType.LOW), store.unplayableTypes());
        store.commit(SoundLibrary.EMPTY);
        assertTrue(store.unplayableTypes().isEmpty());
    }

    @Test
    public void aTempFileThatCannotBeCreatedIsMappedToStorageException() throws IOException {
        File blocker = new File(folder.getRoot(), "alarm_sounds");
        Files.write(blocker.toPath(), new byte[] {1});   // a file where the directory should be
        open();
        try {
            store.newTempFile();
            fail("expected StorageException");
        } catch (StorageException expected) {
            assertFalse(logger.lines.isEmpty());
        }
    }
}
