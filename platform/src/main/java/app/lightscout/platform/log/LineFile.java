package app.lightscout.platform.log;

import java.io.IOException;
import java.util.List;

/**
 * Line-oriented file seam of the ring buffer. Implementations are not thread-safe; the logger calls them from its
 * single writer thread only.
 */
public interface LineFile {

    /**
     * Returns the current size.
     *
     * @return size in bytes; 0 when the file does not exist
     * @throws IOException on I/O failure
     */
    long length() throws IOException;

    /**
     * Reads all lines.
     *
     * @return the lines in file order; empty when the file does not exist
     * @throws IOException on I/O failure
     */
    List<String> readLines() throws IOException;

    /**
     * Appends one line and a line terminator, creating the file if needed.
     *
     * @param line the line (no terminator)
     * @throws IOException on I/O failure
     */
    void append(String line) throws IOException;

    /**
     * Replaces the whole content atomically.
     *
     * @param lines the new lines
     * @throws IOException on I/O failure
     */
    void rewrite(List<String> lines) throws IOException;
}
