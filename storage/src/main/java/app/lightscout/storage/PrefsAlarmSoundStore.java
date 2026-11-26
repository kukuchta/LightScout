package app.lightscout.storage;

import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundResolver;
import app.lightscout.domain.port.AlarmSoundStore;
import app.lightscout.domain.sound.AlarmSoundChoice;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.PlayableSound;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundRef;

/**
 * {@link AlarmSoundStore} (IF-67) and {@link AlarmSoundResolver} (IF-69) in one object (DCR-51): the sound library in
 * its own {@code alarm_sounds} preferences (ST-09, credential-encrypted, after unlock only), the files in
 * {@link SoundFiles}. Keys: {@code schema}, one {@code choice.<TYPE>} per alarm type (absent = classic) and
 * {@code library} (JSON, {@link SoundLibraryJson}). Not part of the reset on a server change.
 *
 * <p>The alarm reads an immutable {@link SoundLibrary} snapshot, loaded on first use and replaced only after a
 * {@code commit()} has succeeded, so a failed write leaves the alarm with the choice it had. The first load reads the
 * preferences file on the calling thread (the player thread or an IO thread, never main).
 */
final class PrefsAlarmSoundStore implements AlarmSoundStore, AlarmSoundResolver {

    /** ST-09 key: sound of the Urgent low alarm. */
    static final String KEY_CHOICE_URGENT_LOW = "choice.URGENT_LOW";
    /** ST-09 key: sound of the Low alarm. */
    static final String KEY_CHOICE_LOW = "choice.LOW";
    /** ST-09 key: sound of the No data alarm. */
    static final String KEY_CHOICE_NO_DATA = "choice.NO_DATA";
    /** ST-09 key: sound of the Not running alarm. */
    static final String KEY_CHOICE_NOT_RUNNING = "choice.NOT_RUNNING";
    /** ST-09 key: sound of the High alarm. */
    static final String KEY_CHOICE_HIGH = "choice.HIGH";
    /** ST-09 key: own sounds, phone copies and the removal that can be undone (JSON). */
    static final String KEY_LIBRARY = "library";

    private static final String OP_READ = "alarmSounds.read";
    private static final String OP_COMMIT = "alarmSounds.commit";
    private static final String OP_TEMP = "alarmSounds.temp";
    private static final String OP_PUBLISH = "alarmSounds.publish";

    private final SharedPreferences prefs;
    private final SoundFiles files;
    private final StoreGuard guard;
    private final AtomicReference<SoundLibrary> snapshot = new AtomicReference<>();
    private final Object writeLock = new Object();
    private final Object unplayableLock = new Object();
    private final EnumSet<AlarmType> unplayable = EnumSet.noneOf(AlarmType.class);

    /**
     * @param prefs the CE {@code alarm_sounds} preferences
     * @param files the sound directory
     * @param guard failure logging and mapping (R-20)
     */
    PrefsAlarmSoundStore(SharedPreferences prefs, SoundFiles files, StoreGuard guard) {
        this.prefs = prefs;
        this.files = files;
        this.guard = guard;
    }

    @Override
    public SoundLibrary read() {
        synchronized (writeLock) {
            try {
                SoundLibrary loaded = load();
                snapshot.set(loaded);
                return loaded;
            } catch (RuntimeException e) {
                throw guard.fail(OP_READ, e);
            }
        }
    }

    @Override
    public void commit(SoundLibrary next) {
        synchronized (writeLock) {
            write(next);
        }
    }

    /** Runs inside {@code writeLock}: a {@link #read()} can never put an older library over a newer commit. */
    private void write(SoundLibrary next) {
        boolean written;
        try {
            SharedPreferences.Editor editor = prefs.edit();
            for (AlarmType type : AlarmType.values()) {
                SoundRef ref = next.choice().get(type);
                if (SoundRef.CLASSIC.equals(ref)) {
                    editor.remove(keyFor(type));
                } else {
                    editor.putString(keyFor(type), ref.encode());
                }
            }
            editor.putString(KEY_LIBRARY, SoundLibraryJson.write(next));
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (RuntimeException e) {
            throw guard.fail(OP_COMMIT, e);
        }
        if (!written) {
            throw guard.fail(OP_COMMIT, null);
        }
        snapshot.set(next);
        synchronized (unplayableLock) {
            unplayable.clear();
        }
    }

    @Override
    public File newTempFile() {
        try {
            return files.newTempFile();
        } catch (IOException | RuntimeException e) {
            throw guard.fail(OP_TEMP, e);
        }
    }

    @Override
    public String publish(File temp, String fileName) {
        try {
            return files.publish(temp, fileName);
        } catch (IOException e) {
            throw guard.fail(OP_PUBLISH, e);
        }
    }

    @Override
    public void deleteFiles(Set<String> fileNames) {
        Set<String> stillUsed;
        try {
            stillUsed = snapshot().referencedFiles();
        } catch (RuntimeException e) {
            throw guard.fail(OP_READ, e);
        }
        Set<String> doomed = new HashSet<>(fileNames);
        doomed.removeAll(stillUsed);
        files.delete(doomed);
    }

    /**
     * Keeps the listed files and every file the committed library needs, so a sweep never removes a sound the alarm
     * can play or a removal that can be undone. Run it at start-up only: it also deletes every unfinished copy in
     * {@code tmp/} and every file no library entry names, which is a copy of an import that is still running.
     */
    @Override
    public void sweep(Set<String> keep) {
        Set<String> kept = new HashSet<>(keep);
        try {
            kept.addAll(snapshot().referencedFiles());
        } catch (RuntimeException e) {
            throw guard.fail(OP_READ, e);
        }
        files.sweep(kept);
    }

    @Override
    public PlayableSound playable(AlarmType type) {
        try {
            SoundLibrary library = snapshot();
            SoundRef ref = library.choice().get(type);
            switch (ref.kind()) {
                case BUNDLED:
                    return PlayableSound.bundled(ref.key());
                case OWN:
                    return library.ownSound(ref.key()).map(PrefsAlarmSoundStore::fileOf)
                            .map(this::playableFile).orElse(PlayableSound.CLASSIC);
                case PHONE:
                    return library.phoneCopies().stream().filter(copy -> copy.key().equals(ref.key())).findFirst()
                            .map(PrefsAlarmSoundStore::fileOf).map(this::playableFile).orElse(PlayableSound.CLASSIC);
                default:
                    return PlayableSound.CLASSIC;
            }
        } catch (RuntimeException e) {
            return PlayableSound.CLASSIC;   // an alarm is never silent because of the sound store
        }
    }

    @Override
    public void reportUnplayable(AlarmType type) {
        synchronized (unplayableLock) {
            unplayable.add(type);
        }
    }

    /**
     * Returns the alarm types whose chosen sound failed to play since the last commit.
     *
     * @return a copy of the set
     */
    Set<AlarmType> unplayableTypes() {
        synchronized (unplayableLock) {
            return new HashSet<>(unplayable);
        }
    }

    private SoundLibrary snapshot() {
        SoundLibrary current = snapshot.get();
        if (current == null) {
            snapshot.compareAndSet(null, load());
            current = snapshot.get();
        }
        return current;
    }

    private SoundLibrary load() {
        AlarmSoundChoice choice = AlarmSoundChoice.allClassic();
        for (AlarmType type : AlarmType.values()) {
            choice = choice.with(type, SoundRef.parse(prefs.getString(keyFor(type), null)).orElse(SoundRef.CLASSIC));
        }
        return SoundLibraryJson.read(prefs.getString(KEY_LIBRARY, null), choice);
    }

    private PlayableSound playableFile(FileEntry entry) {
        File file = files.fileFor(entry.fileName);
        if (file.isFile() && file.length() == entry.byteLength) {
            return PlayableSound.file(file.getAbsolutePath(), entry.byteLength);
        }
        return PlayableSound.CLASSIC;
    }

    private static FileEntry fileOf(OwnSound sound) {
        return new FileEntry(sound.fileName(), sound.byteLength());
    }

    private static FileEntry fileOf(PhoneCopy copy) {
        return new FileEntry(copy.fileName(), copy.byteLength());
    }

    private static String keyFor(AlarmType type) {
        switch (type) {
            case URGENT_LOW:
                return KEY_CHOICE_URGENT_LOW;
            case LOW:
                return KEY_CHOICE_LOW;
            case NO_DATA:
                return KEY_CHOICE_NO_DATA;
            case NOT_RUNNING:
                return KEY_CHOICE_NOT_RUNNING;
            default:
                return KEY_CHOICE_HIGH;
        }
    }

    /** File name and expected size of a stored sound. */
    private static final class FileEntry {
        private final String fileName;
        private final long byteLength;

        private FileEntry(String fileName, long byteLength) {
            this.fileName = fileName;
            this.byteLength = byteLength;
        }
    }
}
