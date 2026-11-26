package app.lightscout.usecases.sound;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.AlarmSoundStore;
import app.lightscout.domain.port.AudioSourcePort;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.sound.ImportLimits;
import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.OwnSound;
import app.lightscout.domain.sound.PhoneCopy;
import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundLibrary;
import app.lightscout.domain.sound.SoundNames;
import app.lightscout.domain.sound.SoundRef;

/**
 * IF-70 implementation (DCR-51). Every change runs under one lock on {@code ioExecutor}, reads the stored library
 * fresh, and ends with one {@code commit()}. Order for a new file: copy into a temporary file, rename it into place,
 * commit the library, then delete the files the new library no longer needs. A failure before the commit deletes
 * what this call created, so the stored library and the files stay as they were. Failures are logged as
 * {@code SOUND_IMPORT_FAIL:<outcome>} (no name, no URI, SR-07).
 */
public final class DefaultAlarmSoundService implements AlarmSoundService {

    private static final String OWN_FILE_PREFIX = "own-";
    private static final String PHONE_FILE_PREFIX = "phone-";
    private static final int KEY_LENGTH = 16;
    /** A copy that has not finished after this long is cancelled between two reads (20 s). */
    private static final long COPY_TIME_LIMIT_NANOS = 20_000_000_000L;

    private final Executor ioExecutor;
    private final AlarmSoundStore store;
    private final AudioSourcePort source;
    private final Logger logger;
    private final Supplier<String> keys;
    private final Object lock = new Object();

    /**
     * Creates the service.
     *
     * @param ioExecutor executor for all work and callbacks
     * @param store      IF-67
     * @param source     IF-68
     * @param logger     IF-66
     */
    public DefaultAlarmSoundService(Executor ioExecutor, AlarmSoundStore store, AudioSourcePort source,
                                    Logger logger) {
        this(ioExecutor, store, source, logger, DefaultAlarmSoundService::randomKey);
    }

    /** Test seam: the supplier of the 16-hex-digit keys. */
    DefaultAlarmSoundService(Executor ioExecutor, AlarmSoundStore store, AudioSourcePort source, Logger logger,
                             Supplier<String> keys) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.store = Objects.requireNonNull(store, "store");
        this.source = Objects.requireNonNull(source, "source");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    private static String randomKey() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, KEY_LENGTH);
    }

    @Override
    public void load(Consumer<AlarmSoundsView> cb) {
        ioExecutor.execute(() -> cb.accept(new AlarmSoundsView(readOrDefault(), phoneSounds())));
    }

    @Override
    public void select(AlarmType type, SoundRef ref, Consumer<SoundAction> cb) {
        act(cb, false, before -> change(before, before.select(type, ref)));
    }

    @Override
    public void selectPhone(AlarmType type, PhoneSound sound, Consumer<SoundAction> cb) {
        act(cb, true, before -> {
            Optional<PhoneCopy> existing = before.phoneCopyFor(sound.uri());
            if (existing.isPresent()) {
                return change(before, before.select(type, SoundRef.phone(existing.get().key())));
            }
            Copy copy = copyIn(sound.uri());
            if (copy.outcome != ImportOutcome.OK) {
                return failure(copy.outcome, before);
            }
            String key = keys.get();
            String fileName = PHONE_FILE_PREFIX + key;
            SoundLibrary after;
            try {
                PhoneCopy phoneCopy = new PhoneCopy(key, sound.uri(), SoundNames.displayName(sound.title(), 1),
                        fileName, copy.length);
                after = before.addPhoneCopy(phoneCopy).select(type, SoundRef.phone(key));
            } catch (IllegalArgumentException e) {
                copy.temp.delete();
                throw e;
            }
            return publishAndCommit(before, after, copy.temp, fileName);
        });
    }

    @Override
    public void addOwn(String documentUri, String displayName, AlarmType selectFor, Consumer<SoundAction> cb) {
        act(cb, true, before -> {
            if (!before.canAddOwn()) {
                return failure(ImportOutcome.LIMIT_REACHED, before);
            }
            Copy copy = copyIn(documentUri);
            if (copy.outcome != ImportOutcome.OK) {
                return failure(copy.outcome, before);
            }
            String key = keys.get();
            String fileName = OWN_FILE_PREFIX + key;
            SoundLibrary after;
            try {
                OwnSound sound = new OwnSound(key, SoundNames.displayName(displayName, before.ownSounds().size() + 1),
                        fileName, copy.length);
                after = before.addOwn(sound);
                if (selectFor != null) {
                    after = after.select(selectFor, SoundRef.own(key));
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                copy.temp.delete();
                throw e;
            }
            return publishAndCommit(before, after, copy.temp, fileName);
        });
    }

    @Override
    public void remove(String ownKey, Consumer<SoundAction> cb) {
        act(cb, false, before -> change(before, before.remove(ownKey)));
    }

    @Override
    public void undoRemoval(Consumer<SoundAction> cb) {
        act(cb, false, before -> {
            SoundLibrary after = before.undoRemoval();
            if (before.pendingRemoval().isPresent() && after.equals(before)) {
                return new SoundAction(ImportOutcome.CANNOT_READ, before);   // no room to restore it
            }
            return change(before, after);
        });
    }

    @Override
    public void finaliseRemoval(Consumer<SoundAction> cb) {
        act(cb, false, before -> change(before, before.finaliseRemoval()));
    }

    @Override
    public void sweep() {
        ioExecutor.execute(() -> {
            synchronized (lock) {
                try {
                    SoundLibrary stored = store.read();
                    SoundLibrary finalised = stored.finaliseRemoval();
                    commitAndClean(stored, finalised);
                    store.sweep(finalised.referencedFiles());
                } catch (RuntimeException e) {
                    // the store logged a storage failure; nothing is deleted while the library cannot be read
                }
            }
        });
    }

    /** One change of the library: reads it fresh, applies the change, reports the result. */
    private interface Change {
        SoundAction apply(SoundLibrary before);
    }

    private void act(Consumer<SoundAction> cb, boolean importing, Change change) {
        ioExecutor.execute(() -> {
            SoundAction result;
            synchronized (lock) {
                result = guarded(importing, change);
            }
            cb.accept(result);
        });
    }

    private SoundAction guarded(boolean importing, Change change) {
        SoundLibrary before;
        try {
            before = store.read();
        } catch (RuntimeException e) {
            return new SoundAction(ImportOutcome.CANNOT_READ, SoundLibrary.EMPTY);
        }
        try {
            return change.apply(before);
        } catch (RuntimeException e) {
            // a store failure, a refused value or a bug below: the caller still gets its one answer, and a file
            // this call published but did not commit is removed by the next start-up sweep
            return importing ? failure(ImportOutcome.CANNOT_READ, before)
                    : new SoundAction(ImportOutcome.CANNOT_READ, before);
        }
    }

    private SoundAction failure(ImportOutcome outcome, SoundLibrary stored) {
        logger.event(EventCode.SOUND_IMPORT_FAIL, outcome.name());
        return new SoundAction(outcome, stored);
    }

    private SoundAction change(SoundLibrary before, SoundLibrary after) {
        commitAndClean(before, after);
        return new SoundAction(ImportOutcome.OK, after);
    }

    private SoundAction publishAndCommit(SoundLibrary before, SoundLibrary after, File temp, String fileName) {
        try {
            store.publish(temp, fileName);
        } catch (StorageException e) {
            temp.delete();
            throw e;
        }
        try {
            commitAndClean(before, after);
        } catch (StorageException e) {
            store.deleteFiles(Set.of(fileName));   // not referenced by the stored library: removed
            throw e;
        }
        return new SoundAction(ImportOutcome.OK, after);
    }

    private void commitAndClean(SoundLibrary before, SoundLibrary after) {
        if (after.equals(before)) {
            return;
        }
        store.commit(after);
        Set<String> unused = new HashSet<>(before.referencedFiles());
        unused.removeAll(after.referencedFiles());
        if (!unused.isEmpty()) {
            try {
                store.deleteFiles(unused);
            } catch (RuntimeException e) {
                // the change is committed: a file that stays is an orphan the next start-up sweep removes
            }
        }
    }

    /** A source copied into a temporary file; the file is deleted again when the copy is not usable. */
    private static final class Copy {
        private final ImportOutcome outcome;
        private final File temp;
        private final long length;

        private Copy(ImportOutcome outcome, File temp, long length) {
            this.outcome = outcome;
            this.temp = temp;
            this.length = length;
        }
    }

    private Copy copyIn(String uri) {
        File temp = store.newTempFile();
        ImportOutcome outcome;
        try {
            long deadline = System.nanoTime() + COPY_TIME_LIMIT_NANOS;
            outcome = source.copyTo(uri, temp, ImportLimits.MAX_SOURCE_BYTES,
                    () -> System.nanoTime() - deadline > 0);   // a slow provider cannot hold the lock for ever
        } catch (RuntimeException e) {
            outcome = ImportOutcome.CANNOT_READ;
        }
        long length = temp.length();
        if (outcome == ImportOutcome.OK && length < 1) {
            outcome = ImportOutcome.NOT_PLAYABLE;
        } else if (outcome == ImportOutcome.OK && length > ImportLimits.MAX_SOURCE_BYTES) {
            outcome = ImportOutcome.TOO_LARGE;
        }
        if (outcome != ImportOutcome.OK) {
            temp.delete();
        }
        return new Copy(outcome, temp, length);
    }

    private SoundLibrary readOrDefault() {
        try {
            return store.read();
        } catch (StorageException e) {
            return SoundLibrary.EMPTY;   // the store logged it; the screen shows the default
        }
    }

    private List<PhoneSound> phoneSounds() {
        try {
            return source.phoneAlarmSounds();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
