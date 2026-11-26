package app.lightscout.storage;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The sound directory ST-10 (DCR-51): {@code <filesDir>/alarm_sounds/} with a {@code tmp/} folder for copies in
 * progress. Files are named by a plain name only ({@code a-z 0-9 _ -}, never a path), so a stored or supplied name can
 * never reach outside the directory. A copy is written under {@code tmp/}, flushed to disk and then renamed into place
 * in one step, so a final file is always complete.
 *
 * <p>Constructing does nothing on disk. Every method may touch the disk: call off the main thread.
 */
final class SoundFiles {

    private static final Pattern PLAIN_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final String TEMP_DIR = "tmp";
    private static final String TEMP_SUFFIX = ".part";

    private final File root;

    /**
     * @param root the sound directory; it is created when the first copy starts
     */
    SoundFiles(File root) {
        this.root = root;
    }

    File root() {
        return root;
    }

    /**
     * Creates an empty temporary file for a copy in progress.
     *
     * @return the file inside {@code tmp/}
     * @throws IOException when the folder or the file cannot be created
     */
    File newTempFile() throws IOException {
        File temp = tempDir();
        if (!temp.isDirectory() && !temp.mkdirs() && !temp.isDirectory()) {
            throw new IOException("Cannot create the temporary sound folder");
        }
        File file = new File(temp, UUID.randomUUID() + TEMP_SUFFIX);
        if (!file.createNewFile()) {
            throw new IOException("Temporary sound file exists");
        }
        return file;
    }

    /**
     * Flushes a finished temporary file to disk and renames it into the sound directory.
     *
     * @param temp     file from {@link #newTempFile()}
     * @param fileName plain final name
     * @return the plain final name
     * @throws IllegalArgumentException when the name is not plain or the file is not one of the temporary files
     * @throws IOException              when a file of that name exists (it is never replaced), or the flush or the
     *                                  rename fails
     */
    String publish(File temp, String fileName) throws IOException {
        File target = fileFor(fileName);
        if (!tempDir().equals(temp.getParentFile())) {
            throw new IllegalArgumentException("Not a temporary sound file");
        }
        if (target.exists()) {
            throw new FileAlreadyExistsException(fileName);   // a rename would silently replace a live sound
        }
        try (FileChannel channel = FileChannel.open(temp.toPath(), StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return fileName;
    }

    /**
     * Returns the file of a plain name.
     *
     * @param fileName plain name
     * @return the file inside the sound directory
     * @throws IllegalArgumentException when the name is not plain
     */
    File fileFor(String fileName) {
        if (fileName == null || !PLAIN_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Sound file name must be a plain name");
        }
        return new File(root, fileName);
    }

    /**
     * Deletes the named files. A name that is not plain or a file that is already gone is skipped; a file that cannot
     * be deleted stays and the next {@link #sweep} removes it.
     *
     * @param fileNames plain file names
     */
    void delete(Set<String> fileNames) {
        for (String name : fileNames) {
            if (name != null && PLAIN_NAME.matcher(name).matches()) {
                new File(root, name).delete();
            }
        }
    }

    /**
     * Deletes every file in the sound directory that is not listed, and every unfinished temporary file.
     *
     * @param keep plain file names to keep
     */
    void sweep(Set<String> keep) {
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (TEMP_DIR.equals(child.getName())) {
                    deleteChildren(child);
                }
            } else if (!keep.contains(child.getName())) {
                child.delete();
            }
        }
    }

    private File tempDir() {
        return new File(root, TEMP_DIR);
    }

    private static void deleteChildren(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
    }
}
