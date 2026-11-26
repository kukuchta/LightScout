package app.lightscout.domain.port;

import java.io.File;
import java.util.Set;

import app.lightscout.domain.sound.SoundLibrary;

/**
 * Store of the sound library and of the sound files (IF-67, DCR-51), implemented by {@code :storage} in its own
 * preferences file and sound directory (credential-encrypted storage, after unlock only). Blocking: call off the main
 * thread. Files are only ever named by a plain file name; the store never accepts a path.
 */
public interface AlarmSoundStore {

    /**
     * Reads the library. A damaged library reads as {@link SoundLibrary#EMPTY} repaired by {@link SoundLibrary#of}.
     *
     * @return the library
     * @throws StorageException when storage cannot be read
     */
    SoundLibrary read();

    /**
     * Replaces the stored library in one atomic write.
     *
     * @param next the new library
     * @throws StorageException when the write fails; the stored library is then unchanged
     */
    void commit(SoundLibrary next);

    /**
     * Creates an empty temporary file for a copy in progress.
     *
     * @return the file, inside the sound directory's temporary folder
     * @throws StorageException when it cannot be created
     */
    File newTempFile();

    /**
     * Moves a finished temporary file into the sound directory under its final name.
     *
     * @param temp     file from {@link #newTempFile()}
     * @param fileName plain final name
     * @return the final plain name
     * @throws StorageException when the move fails
     */
    String publish(File temp, String fileName);

    /**
     * Deletes sound files. Names that the committed library still references are not deleted.
     *
     * @param fileNames plain file names
     */
    void deleteFiles(Set<String> fileNames);

    /**
     * Deletes every file in the sound directory that is not in the given set, and every unfinished temporary file.
     *
     * @param keep plain file names to keep, normally {@link SoundLibrary#referencedFiles()}
     */
    void sweep(Set<String> keep);
}
