package app.lightscout.platform.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link LineFile} for tests; tracks the size, the maximum size ever reached and whether it was touched.
 */
final class MemoryLineFile implements LineFile {

    final List<String> lines = new ArrayList<>();
    long maxLength;
    int rewrites;
    boolean touched;
    boolean failing;
    private long size = -1;

    @Override
    public long length() throws IOException {
        check();
        if (size < 0) {
            size = 0;
            for (String line : lines) {
                size += bytes(line);
            }
        }
        return size;
    }

    @Override
    public List<String> readLines() throws IOException {
        check();
        return new ArrayList<>(lines);
    }

    @Override
    public void append(String line) throws IOException {
        long current = length();
        lines.add(line);
        size = current + bytes(line);
        maxLength = Math.max(maxLength, size);
    }

    @Override
    public void rewrite(List<String> newLines) throws IOException {
        check();
        rewrites++;
        lines.clear();
        lines.addAll(newLines);
        size = -1;
        maxLength = Math.max(maxLength, length());
    }

    private static long bytes(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private void check() throws IOException {
        touched = true;
        if (failing) {
            throw new IOException("disk full at /data/user/0/app.lightscout/files");
        }
    }
}
