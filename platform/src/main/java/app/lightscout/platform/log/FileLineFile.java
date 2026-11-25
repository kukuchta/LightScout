package app.lightscout.platform.log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link LineFile} on a plain app-private file (UTF-8, {@code \n} terminators). {@link #rewrite} writes a sibling
 * temporary file and renames it over the original.
 */
public final class FileLineFile implements LineFile {

    private static final byte[] NEWLINE = {'\n'};

    private final File file;

    /**
     * Creates the seam for a file.
     *
     * @param file the file; its parent directory must exist
     */
    public FileLineFile(File file) {
        this.file = file;
    }

    @Override
    public long length() {
        return file.length();
    }

    @Override
    public List<String> readLines() throws IOException {
        List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Override
    public void append(String line) throws IOException {
        try (OutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write(NEWLINE);
        }
    }

    @Override
    public void rewrite(List<String> lines) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp, false)) {
            for (String line : lines) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write(NEWLINE);
            }
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
