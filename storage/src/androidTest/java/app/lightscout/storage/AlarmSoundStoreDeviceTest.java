package app.lightscout.storage;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.AlarmSoundStore;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PlayableSound;
import app.lightscout.domain.sound.SoundKind;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * DCR-51 S3 on a real device (RD-1): the sound library in real preferences and real files. Synthetic bytes only; the
 * test APK has its own data directory, so it never touches the installed app. Every run ends with {@code adb
 * uninstall} of the test APK (SR-34).
 */
@RunWith(AndroidJUnit4.class)
public class AlarmSoundStoreDeviceTest {

    private static final String KEY = "00000000000000a1";
    private static final String FILE = "own-" + KEY;
    private static final byte[] BYTES = {1, 2, 3, 4, 5, 6, 7, 8};

    private Context context;
    private StorageFactory factory;

    @Before
    public void setUp() {
        context = StorageTestSupport.context();
        StorageTestSupport.wipe(context);
        factory = newFactory();
    }

    @After
    public void tearDown() {
        StorageTestSupport.wipe(context);
    }

    private StorageFactory newFactory() {
        return new StorageFactory(context, new StorageTestSupport.TestClock(), new StorageTestSupport.TestLogger());
    }

    private File soundDir() {
        return new File(context.getFilesDir(), StorageFactory.SOUND_DIR);
    }

    private void publishSound(AlarmSoundStore store) throws IOException {
        File temp = store.newTempFile();
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(BYTES);
        }
        store.publish(temp, FILE);
    }

    private static SoundLibrary libraryWithOwnOnLow() {
        return SoundLibrary.EMPTY.addOwn(new OwnSound(KEY, "Test sound", FILE, BYTES.length))
                .select(AlarmType.LOW, SoundRef.own(KEY));
    }

    @Test
    public void aCommittedChoiceSurvivesANewFactoryAndPlaysFromTheFile() throws IOException {
        AlarmSoundStore store = factory.alarmSounds();
        publishSound(store);
        store.commit(libraryWithOwnOnLow());

        AlarmSoundResolver resolver = newFactory().alarmSoundResolver();
        PlayableSound playable = resolver.playable(AlarmType.LOW);
        assertEquals(SoundKind.OWN, playable.kind());
        assertEquals(new File(soundDir(), FILE).getAbsolutePath(), playable.name());
        assertEquals(BYTES.length, playable.byteLength());
        assertEquals(PlayableSound.CLASSIC, resolver.playable(AlarmType.HIGH));
    }

    @Test
    public void storeAndResolverAreOneObject() {
        assertSame(factory.alarmSounds(), factory.alarmSoundResolver());
        assertSame(factory.alarmSounds(), factory.alarmSounds());
    }

    @Test
    public void theSweepRemovesOrphansAndUnfinishedCopiesButKeepsTheUsedFile() throws IOException {
        AlarmSoundStore store = factory.alarmSounds();
        publishSound(store);
        File unfinished = store.newTempFile();
        File orphan = new File(soundDir(), "own-orphan");
        assertTrue(orphan.createNewFile());
        SoundLibrary library = libraryWithOwnOnLow();
        store.commit(library);

        store.sweep(library.referencedFiles());

        assertTrue(new File(soundDir(), FILE).isFile());
        assertFalse(orphan.exists());
        assertFalse(unfinished.exists());
    }

    @Test
    public void aFileThatWasTruncatedAfterTheCommitFallsBackToClassic() throws IOException {
        AlarmSoundStore store = factory.alarmSounds();
        publishSound(store);
        store.commit(libraryWithOwnOnLow());
        try (FileOutputStream out = new FileOutputStream(new File(soundDir(), FILE))) {
            out.write(BYTES, 0, 3);
        }
        assertEquals(PlayableSound.CLASSIC, newFactory().alarmSoundResolver().playable(AlarmType.LOW));
    }

    @Test
    public void thePreferencesAndTheFilesAreNotAccessibleToOtherApps() throws IOException {
        AlarmSoundStore store = factory.alarmSounds();
        publishSound(store);
        store.commit(libraryWithOwnOnLow());
        File prefs = new File(new File(context.getDataDir(), "shared_prefs"),
                StorageFactory.ALARM_SOUNDS_PREFS + ".xml");
        for (File file : new File[] {prefs, soundDir(), new File(soundDir(), FILE)}) {
            assertTrue(file.getPath(), file.exists());
            assertFalse(file.getPath() + " accessible to others", hasOtherAccess(file));
        }
    }

    private static boolean hasOtherAccess(File file) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file.toPath());
        return permissions.contains(PosixFilePermission.OTHERS_READ)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
    }
}
